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

package com.tokenbrowser.manager;


import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;

import com.tokenbrowser.BuildConfig;
import com.tokenbrowser.R;
import com.tokenbrowser.crypto.HDWallet;
import com.tokenbrowser.crypto.signal.ChatService;
import com.tokenbrowser.crypto.signal.SignalPreferences;
import com.tokenbrowser.crypto.signal.model.DecryptedSignalMessage;
import com.tokenbrowser.crypto.signal.store.ProtocolStore;
import com.tokenbrowser.crypto.signal.store.SignalTrustStore;
import com.tokenbrowser.exception.GroupCreationException;
import com.tokenbrowser.manager.chat.SofaMessageReceiver;
import com.tokenbrowser.manager.chat.SofaMessageRegistration;
import com.tokenbrowser.manager.model.SofaMessageTask;
import com.tokenbrowser.manager.store.ConversationStore;
import com.tokenbrowser.manager.store.PendingMessageStore;
import com.tokenbrowser.model.local.Conversation;
import com.tokenbrowser.model.local.Group;
import com.tokenbrowser.model.local.PendingMessage;
import com.tokenbrowser.model.local.SendState;
import com.tokenbrowser.model.local.User;
import com.tokenbrowser.model.sofa.OutgoingAttachment;
import com.tokenbrowser.model.sofa.SofaMessage;
import com.tokenbrowser.util.FileNames;
import com.tokenbrowser.util.LogUtil;
import com.tokenbrowser.view.BaseApplication;

import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.util.Hex;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.EncapsulatedExceptions;
import org.whispersystems.signalservice.internal.push.SignalServiceUrl;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import rx.Completable;
import rx.Observable;
import rx.Single;
import rx.Subscription;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

import static com.tokenbrowser.util.FileUtil.buildSignalServiceAttachment;

public final class SofaMessageManager {
    private final PublishSubject<SofaMessageTask> chatMessageQueue = PublishSubject.create();
    private final ConversationStore conversationStore;
    private final PendingMessageStore pendingMessageStore;
    private final SharedPreferences sharedPreferences;
    private final SignalServiceUrl[] signalServiceUrls;
    private final CompositeSubscription subscriptions;
    private final String userAgent;

    private ChatService chatService;
    private Subscription handleMessageSubscription;
    private ProtocolStore protocolStore;
    private SofaMessageReceiver messageReceiver;
    private SofaMessageRegistration sofaGcmRegister;
    private HDWallet wallet;

    /*package*/ SofaMessageManager() {
        this.conversationStore = new ConversationStore();
        this.pendingMessageStore = new PendingMessageStore();
        this.userAgent = "Android " + BuildConfig.APPLICATION_ID + " - " + BuildConfig.VERSION_NAME +  ":" + BuildConfig.VERSION_CODE;
        this.signalServiceUrls = new SignalServiceUrl[1];
        this.sharedPreferences = BaseApplication.get().getSharedPreferences(FileNames.GCM_PREFS, Context.MODE_PRIVATE);
        this.subscriptions = new CompositeSubscription();
    }

    public final SofaMessageManager init(final HDWallet wallet) {
        this.wallet = wallet;
        new Thread(this::initEverything).start();
        return this;
    }

    // Will send the message to a remote peer
    // and store the message in the local database
    public final void sendAndSaveMessage(final User receiver, final SofaMessage message) {
        final SofaMessageTask messageTask = new SofaMessageTask(receiver, message, SofaMessageTask.SEND_AND_SAVE);
        this.chatMessageQueue.onNext(messageTask);
    }

    // Will send the message to a remote peer
    // but not store the message in the local database
    public final void sendMessage(final User receiver, final SofaMessage message) {
        final SofaMessageTask messageTask = new SofaMessageTask(receiver, message, SofaMessageTask.SEND_ONLY);
        this.chatMessageQueue.onNext(messageTask);
    }

    // Will store the message in the local database
    // but not send the message to a remote peer
    public final void saveMessage(final User receiver, final SofaMessage message) {
        final SofaMessageTask messageTask = new SofaMessageTask(receiver, message, SofaMessageTask.SAVE_ONLY);
        this.chatMessageQueue.onNext(messageTask);
    }

    // Create a new group
    public final Single<Group> createGroup(final Group group) {
        return sendMessageToGroup(group);
    }

    // Will store a transaction in the local database
    // but not send the message to a remote peer. It will also save the state as "SENDING".
    /* package */ final void saveTransaction(final User receiver, final SofaMessage message) {
        final SofaMessageTask messageTask = new SofaMessageTask(receiver, message, SofaMessageTask.SAVE_TRANSACTION);
        this.chatMessageQueue.onNext(messageTask);
    }

    // Updates a pre-existing message.
    /* package */ final void updateMessage(final User receiver, final SofaMessage message) {
        final SofaMessageTask messageTask = new SofaMessageTask(receiver, message, SofaMessageTask.UPDATE_MESSAGE);
        this.chatMessageQueue.onNext(messageTask);
    }

    public final void resumeMessageReceiving() {
        if (haveRegisteredWithServer() && this.wallet != null && this.messageReceiver != null) {
            this.messageReceiver.receiveMessagesAsync();
        }
    }

    private boolean haveRegisteredWithServer() {
        return SignalPreferences.getRegisteredWithServer();
    }

    public final void disconnect() {
        if (this.messageReceiver != null) {
            this.messageReceiver.shutdown();
        }
    }

    public final Single<List<Conversation>> loadAllConversations() {
        return Single
                .fromCallable(conversationStore::loadAll)
                .subscribeOn(Schedulers.io());
    }

    public final Single<Conversation> loadConversation(final String threadId) {
        return this.conversationStore.loadByThreadId(threadId)
                .subscribeOn(Schedulers.io());
    }

    public final Observable<Conversation> registerForAllConversationChanges() {
        return this.conversationStore.getConversationChangedObservable();
    }

    // Returns a pair of RxSubjects, the first being the observable for new messages
    // the second being the observable for updated messages.
    public final Pair<PublishSubject<SofaMessage>, PublishSubject<SofaMessage>> registerForConversationChanges(final String threadId) {
        return this.conversationStore.registerForChanges(threadId);
    }

    public final void stopListeningForChanges() {
        this.conversationStore.stopListeningForChanges();
    }

    public final Single<Boolean> areUnreadMessages() {
        return Single
                .fromCallable(conversationStore::areUnreadMessages)
                .subscribeOn(Schedulers.io());
    }

    private void initEverything() {
        generateStores();
        initMessageReceiver();
        initRegistrationTask();
        attachSubscribers();
    }

    private void generateStores() {
        this.protocolStore = new ProtocolStore().init();
        final SignalTrustStore trustStore = new SignalTrustStore();

        final SignalServiceUrl signalServiceUrl = new SignalServiceUrl(
                BaseApplication.get().getResources().getString(R.string.chat_url),
                trustStore);
        this.signalServiceUrls[0] = signalServiceUrl;
        this.chatService = new ChatService(this.signalServiceUrls, this.wallet, this.protocolStore, this.userAgent);

    }

    private void initMessageReceiver() {
        this.messageReceiver = new SofaMessageReceiver(
                this.wallet,
                this.protocolStore,
                this.conversationStore,
                this.signalServiceUrls);
    }

    private void initRegistrationTask() {
        this.sofaGcmRegister = new SofaMessageRegistration(this.sharedPreferences, this.chatService, this.protocolStore);
        this.sofaGcmRegister
                .registerIfNeeded()
                .subscribe(
                        this.messageReceiver::receiveMessagesAsync,
                        ex -> LogUtil.e(getClass(), "Error during registration: " + ex)
                );
    }

    public Completable tryUnregisterGcm() {
        if (this.sofaGcmRegister == null) {
            return Completable.error(new NullPointerException("Unable to register as class hasn't been initialised yet."));
        }
        return this.sofaGcmRegister.tryUnregisterGcm();
    }

    public void setGcmToken(final String token) {
        if (this.sofaGcmRegister == null) {
            LogUtil.e(getClass(), "Unable to setGcmToken as class hasn't been initialised yet.");
        }
        this.sofaGcmRegister.setGcmToken(token);
    }

    private void attachSubscribers() {
        if (this.handleMessageSubscription != null) {
            this.handleMessageSubscription.unsubscribe();
        }

        this.handleMessageSubscription =
                this.chatMessageQueue
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .subscribe(
                        this::handleMessage,
                        this::handleMessageError
                );

        this.subscriptions.add(this.handleMessageSubscription);

        BaseApplication
                .get()
                .isConnectedSubject()
                .filter(isConnected -> isConnected)
                .onErrorReturn(__ -> false)
                .subscribe(
                        isConnected -> sendPendingMessages(),
                        this::handleConnectionStateError
                );
    }

    private void handleMessage(final SofaMessageTask messageTask) {
        switch (messageTask.getAction()) {
            case SofaMessageTask.SEND_AND_SAVE:
                sendMessageToRemotePeer(messageTask, true);
                break;
            case SofaMessageTask.SAVE_ONLY:
                storeMessage(messageTask.getReceiver(), messageTask.getSofaMessage(), SendState.STATE_LOCAL_ONLY);
                break;
            case SofaMessageTask.SAVE_TRANSACTION:
                storeMessage(messageTask.getReceiver(), messageTask.getSofaMessage(), SendState.STATE_SENDING);
                break;
            case SofaMessageTask.SEND_ONLY:
                sendMessageToRemotePeer(messageTask, false);
                break;
            case SofaMessageTask.UPDATE_MESSAGE:
                updateExistingMessage(messageTask.getReceiver(), messageTask.getSofaMessage());
                break;
        }
    }

    private void handleMessageError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Message sending/receiving now broken due to this error", throwable);
    }

    private void sendPendingMessages() {
        final List<PendingMessage> pendingMessages = this.pendingMessageStore.fetchAllPendingMessages();
        for (final PendingMessage pendingMessage : pendingMessages) {
            sendAndSaveMessage(pendingMessage.getReceiver(), pendingMessage.getSofaMessage());
        }
    }

    private void handleConnectionStateError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error during checking connection state", throwable);
    }

    private void sendMessageToRemotePeer(final SofaMessageTask messageTask, final boolean saveMessageToDatabase) {
        final User receiver = messageTask.getReceiver();
        final SofaMessage message = messageTask.getSofaMessage();

        if (saveMessageToDatabase) {
            this.conversationStore.saveNewMessage(receiver, message);
        }

        if (!BaseApplication.get().isConnected() && saveMessageToDatabase) {
            message.setSendState(SendState.STATE_PENDING);
            updateExistingMessage(receiver, message);
            savePendingMessage(receiver, message);
            return;
        }

        try {
            sendToSignal(messageTask);

            if (saveMessageToDatabase) {
                message.setSendState(SendState.STATE_SENT);
                updateExistingMessage(receiver, message);
            }
        } catch (final UntrustedIdentityException ue) {
            LogUtil.error(getClass(), "Keys have changed. " + ue);
            protocolStore.saveIdentity(
                    new SignalProtocolAddress(receiver.getTokenId(), SignalServiceAddress.DEFAULT_DEVICE_ID),
                    ue.getIdentityKey());
        } catch (final IOException ex) {
            LogUtil.error(getClass(), ex.toString());
            if (saveMessageToDatabase) {
                message.setSendState(SendState.STATE_FAILED);
                updateExistingMessage(receiver, message);
            }
        }
    }

    private Single<Group> sendMessageToGroup(final Group group) {
        return Single.fromCallable(() -> {
                try {
                    final SignalServiceGroup signalGroup = new SignalServiceGroup(
                            SignalServiceGroup.Type.UPDATE,
                            Hex.fromStringCondensed(group.getId()),
                            group.getTitle(),
                            group.getMemberIds(),
                            group.getAvatar().getStream());
                    final SignalServiceDataMessage groupDataMessage = new SignalServiceDataMessage(System.currentTimeMillis(), signalGroup, null, null);

                    generateMessageSender().sendMessage(group.getMemberAddresses(), groupDataMessage);
                    return group;
                } catch (final IOException | EncapsulatedExceptions ex) {
                    throw new GroupCreationException(ex);
                }
            })
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .doOnSuccess(this.conversationStore::saveNewGroup);
    }

    private void sendToSignal(final SofaMessageTask messageTask) throws UntrustedIdentityException, IOException {
        final SignalServiceAddress receivingAddress = new SignalServiceAddress(messageTask.getReceiver().getTokenId());
        final SignalServiceDataMessage message = buildMessage(messageTask);
        generateMessageSender().sendMessage(receivingAddress, message);
    }

    private SignalServiceMessageSender generateMessageSender() {
        return new SignalServiceMessageSender(
                this.signalServiceUrls,
                this.wallet.getOwnerAddress(),
                this.protocolStore.getPassword(),
                this.protocolStore,
                this.userAgent,
                Optional.absent(),
                Optional.absent()
        );
    }

    private SignalServiceDataMessage buildMessage(final SofaMessageTask messageTask) throws FileNotFoundException {
        final SignalServiceDataMessage.Builder messageBuilder = SignalServiceDataMessage.newBuilder();
        messageBuilder.withBody(messageTask.getSofaMessage().getAsSofaMessage());
        final OutgoingAttachment outgoingAttachment = new OutgoingAttachment(messageTask.getSofaMessage());

        try {
            if (outgoingAttachment.isValid()) {
                final SignalServiceAttachment signalAttachment = buildSignalServiceAttachment(outgoingAttachment);
                messageBuilder.withAttachment(signalAttachment);
            }
        } catch (final FileNotFoundException | IllegalStateException ex) {
            LogUtil.i(getClass(), "Tried and failed to attach attachment." + ex);
        }

        return messageBuilder.build();
    }



    private void storeMessage(final User receiver, final SofaMessage message, final @SendState.State int sendState) {
        message.setSendState(sendState);
        this.conversationStore.saveNewMessage(receiver, message);
    }

    private void updateExistingMessage(final User receiver, final SofaMessage message) {
        this.conversationStore.updateMessage(receiver, message);
    }

    private void savePendingMessage(final User receiver, final SofaMessage message) {
        this.pendingMessageStore.save(receiver, message);
    }

    public DecryptedSignalMessage fetchLatestMessage() throws TimeoutException {
        try {
            while (this.messageReceiver == null) {
                Thread.sleep(200);
            }
        } catch (final InterruptedException e) {
            throw new TimeoutException(e.toString());
        }
        return this.messageReceiver.fetchLatestMessage();
    }

    public void clear() {
        clearMessageReceiver();
        clearSubscriptions();
        this.protocolStore.deleteAllSessions();
        this.sharedPreferences
                .edit()
                .clear()
                .apply();
    }

    private void clearMessageReceiver() {
        if (this.messageReceiver != null) {
            this.messageReceiver.shutdown();
            this.messageReceiver = null;
        }
    }

    private void clearSubscriptions() {
        this.subscriptions.clear();
    }
}
