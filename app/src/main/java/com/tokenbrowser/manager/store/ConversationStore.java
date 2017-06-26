/*
 * 	Copyright (c) 2017. Token Browser, Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tokenbrowser.manager.store;


import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;

import com.tokenbrowser.model.local.Conversation;
import com.tokenbrowser.model.local.Group;
import com.tokenbrowser.model.local.Recipient;
import com.tokenbrowser.model.local.User;
import com.tokenbrowser.model.sofa.SofaMessage;
import com.tokenbrowser.util.LogUtil;
import com.tokenbrowser.view.BaseApplication;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.realm.Realm;
import io.realm.RealmQuery;
import io.realm.RealmResults;
import io.realm.Sort;
import rx.Observable;
import rx.Single;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;

public class ConversationStore {

    private static final int FIFTEEN_MINUTES = 1000 * 60 * 15;
    private static final String THREAD_ID_FIELD = "threadId";

    private static String watchedThreadId;
    private final static PublishSubject<SofaMessage> NEW_MESSAGE_SUBJECT = PublishSubject.create();
    private final static PublishSubject<SofaMessage> UPDATED_MESSAGE_SUBJECT = PublishSubject.create();
    private final static PublishSubject<Conversation> CONVERSATION_CHANGED_SUBJECT = PublishSubject.create();
    private final static ExecutorService dbThread = Executors.newSingleThreadExecutor();

    // Returns a pair of RxSubjects, the first being the observable for new messages
    // the second being the observable for updated messages.
    public Pair<PublishSubject<SofaMessage>, PublishSubject<SofaMessage>> registerForChanges(final String threadId) {
        watchedThreadId = threadId;
        return new Pair<>(NEW_MESSAGE_SUBJECT, UPDATED_MESSAGE_SUBJECT);
    }

    public void stopListeningForChanges() {
        watchedThreadId = null;
    }

    public Observable<Conversation> getConversationChangedObservable() {
        return CONVERSATION_CHANGED_SUBJECT
                .filter(thread -> thread != null);
    }

    public void saveNewGroup(@NonNull final Group group) {
        final Recipient recipient = new Recipient(group);
        saveMessage(recipient)
                .observeOn(Schedulers.immediate())
                .subscribeOn(Schedulers.from(dbThread))
                .subscribe(
                        this::broadcastConversationChanged,
                        this::handleError
                );
    }

    public void saveNewMessage(
            @NonNull final User user,
            @NonNull final SofaMessage message) {
        final Recipient recipient = new Recipient(user);
        saveMessage(recipient, message)
        .observeOn(Schedulers.immediate())
        .subscribeOn(Schedulers.from(dbThread))
        .subscribe(
                conversationForBroadcast -> {
                    broadcastNewChatMessage(user.getTokenId(), message);
                    broadcastConversationChanged(conversationForBroadcast);
                },
                this::handleError
        );
    }

    private Single<Conversation> saveMessage(@NonNull final Recipient recipient) {
        return saveMessage(recipient, null);
    }

    private Single<Conversation> saveMessage(
            @NonNull final Recipient recipient,
            @Nullable final SofaMessage message) {
        return Single.fromCallable(() -> {
            final Conversation conversationToStore = getOrCreateConversation(recipient);

            if (message != null && shouldSaveTimestampMessage(message, conversationToStore)) {
                final SofaMessage timestampMessage =
                        generateTimestampMessage();
                conversationToStore.addMessage(timestampMessage);
                broadcastNewChatMessage(recipient.getThreadId(), timestampMessage);
            }

            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();

            if (message != null) {
                final SofaMessage storedMessage = realm.copyToRealmOrUpdate(message);
                conversationToStore.setLatestMessage(storedMessage);
                conversationToStore.setNumberOfUnread(calculateNumberOfUnread(conversationToStore));
            }

            final Conversation storedConversation = realm.copyToRealmOrUpdate(conversationToStore);
            realm.commitTransaction();
            final Conversation conversationForBroadcast = realm.copyFromRealm(storedConversation);
            realm.close();

            return conversationForBroadcast;
        });
    }

    @NonNull
    private Conversation getOrCreateConversation(final Recipient recipient) {
        final Conversation existingConversation = loadWhere(THREAD_ID_FIELD, recipient.getThreadId());
        return existingConversation == null
                ? new Conversation(recipient)
                : existingConversation;
    }

    private SofaMessage generateTimestampMessage() {
        return new SofaMessage().makeNewTimeStampMessage();
    }

    private boolean shouldSaveTimestampMessage(final SofaMessage message,
                                               final Conversation conversation) {
        final long newMessageTimestamp = message.getCreationTime();
        final long latestMessageTimestamp = conversation.getUpdatedTime();
        return newMessageTimestamp - latestMessageTimestamp > FIFTEEN_MINUTES;
    }

    private int calculateNumberOfUnread(final Conversation conversationToStore) {
        // If we are watching the current thread the message is automatically read.
        if (   conversationToStore == null
            || conversationToStore.getThreadId().equals(watchedThreadId)) {
            return 0;
        }
        final int currentNumberOfUnread = conversationToStore.getNumberOfUnread();
        return currentNumberOfUnread + 1;
    }

    private void resetUnreadMessageCounter(final String threadId) {
        Single.fromCallable(() -> {
            final Conversation storedConversation = loadWhere(THREAD_ID_FIELD, threadId);
            if (storedConversation == null) {
                return null;
            }

            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();
            storedConversation.setNumberOfUnread(0);
            realm.insertOrUpdate(storedConversation);
            realm.commitTransaction();
            realm.close();
            return storedConversation;
        })
        .observeOn(Schedulers.immediate())
        .subscribeOn(Schedulers.from(dbThread))
        .subscribe(
                this::broadcastConversationChanged,
                this::handleError
        );
    }

    public List<Conversation> loadAll() {
        final Realm realm = BaseApplication.get().getRealm();
        final RealmQuery<Conversation> query = realm.where(Conversation.class);
        final RealmResults<Conversation> results = query.findAllSorted("updatedTime", Sort.DESCENDING);
        final List<Conversation> retVal = realm.copyFromRealm(results);
        realm.close();
        return retVal;
    }

    private void broadcastConversationChanged(final Conversation conversation) {
        CONVERSATION_CHANGED_SUBJECT.onNext(conversation);
    }

    public Single<Conversation> loadByThreadId(final String threadId) {
        return Single.fromCallable(() -> {
            resetUnreadMessageCounter(threadId);
            return loadWhere(THREAD_ID_FIELD, threadId);
        });
    }

    private Conversation loadWhere(final String fieldName, final String value) {
        final Realm realm = BaseApplication.get().getRealm();
        final Conversation result = realm
                .where(Conversation.class)
                .equalTo(fieldName, value)
                .findFirst();
        final Conversation retVal = result == null ? null : realm.copyFromRealm(result);
        realm.close();
        return retVal;
    }

    public void updateMessage(final User user, final SofaMessage message) {
        Single.fromCallable(() -> {
            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();
            realm.insertOrUpdate(message);
            realm.commitTransaction();
            realm.close();
            return null;
        })
        .observeOn(Schedulers.immediate())
        .subscribeOn(Schedulers.from(dbThread))
        .subscribe(
                __ -> broadcastUpdatedChatMessage(user.getTokenId(), message),
                this::handleError
        );
    }

    public boolean areUnreadMessages() {
        final Realm realm = BaseApplication.get().getRealm();
        final Conversation result = realm
                .where(Conversation.class)
                .greaterThan("numberOfUnread", 0)
                .findFirst();
        final boolean areUnreadMessages = result != null;
        realm.close();
        return areUnreadMessages;
    }

    private void broadcastNewChatMessage(final String threadId, final SofaMessage newMessage) {
        if (watchedThreadId == null || !watchedThreadId.equals(threadId)) {
            return;
        }
        NEW_MESSAGE_SUBJECT.onNext(newMessage);
    }

    private void broadcastUpdatedChatMessage(final String threadId, final SofaMessage updatedMessage) {
        if (watchedThreadId == null || !watchedThreadId.equals(threadId)) {
            return;
        }
        UPDATED_MESSAGE_SUBJECT.onNext(updatedMessage);
    }

    private void handleError(final Throwable throwable) {
        LogUtil.exception(getClass(), throwable);
    }
}
