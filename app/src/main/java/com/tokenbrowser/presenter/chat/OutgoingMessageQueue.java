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

package com.tokenbrowser.presenter.chat;


import com.tokenbrowser.model.local.Recipient;
import com.tokenbrowser.model.sofa.SofaMessage;
import com.tokenbrowser.util.LogUtil;
import com.tokenbrowser.view.BaseApplication;

import java.util.ArrayList;
import java.util.List;

import rx.Observable;
import rx.Subscription;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

/**
 * A pipeline for sending sofa messages to a remote recipient.
 * It will take care of queuing up and sending messages if messages are sent before
 * the queue has been initialised.
 * It will suppress attempts to double subscribe; and will handle swapping the recipient
 * in the middle of its lifetime.
 * Example usage:
 * <pre> {@code

OutgoingMessageQueue queue = new OutgoingMessageQueue();
queue.send(sofaMessage); // This message will be sent after initialisation
queue.init(recipient); // Let the queue know who these messages should be sent to
queue.send(sofaMessage); // This message will be sent immediately.
queue.clear(); // Cleans up all state, and unsubscribes everything.
} </pre>
 */
/* package */ class OutgoingMessageQueue {

    private final PublishSubject<SofaMessage> messagesReadyForSending;
    private final List<SofaMessage> preInitMessagesQueue;
    private final CompositeSubscription subscriptions;
    private Recipient recipient;

    /**
     * Constructs OutgoingMessageQueue.
     * <p>
     * Nothing will be sent until {@link #init(Recipient)} has been called, but it is possible
     * to queue messages already via {@link #send(SofaMessage)}.
     *
     * @return the constructed OutgoingMessageQueue
     */
    /* package */ OutgoingMessageQueue() {
        this.messagesReadyForSending = PublishSubject.create();
        this.preInitMessagesQueue = new ArrayList<>();
        this.subscriptions = new CompositeSubscription();
    }

    /**
     * Sends or queues a message that may eventually be sent to a remote recipient
     * <p>
     * If {@link #init(Recipient)} has already been called then the message will be sent to the remote recipient
     * immediately.
     * If {@link #init(Recipient)} has not been called then the message will be queued until {@link #init(Recipient)}
     * is called.
     *
     * @param message
     *              The message to be sent.
     */
    /* package */ void send(final SofaMessage message) {
        // If we already know who to send the message to; send it.
        // If not, queue it until we know where to send the message.
        if (this.recipient != null) {
            this.messagesReadyForSending.onNext(message);
        } else {
            this.preInitMessagesQueue.add(message);
        }
    }

    /**
     * Clear all the state; stop processing messages.
     * <p>
     * Any messages not yet sent will be lost. It is wise to call this method when possible to
     * clear any state, and release memory.
     */
    /* package */ void clear() {
        this.subscriptions.clear();
        this.recipient = null;
    }

    /**
     * Initialises OutgoingMessageQueue with the recipient who will receive messages
     * <p>
     * Any messages that have already been queued by previous calls to {@link #send(SofaMessage)}
     * will be processed in the order they were sent. Any new calls to {@link #send(SofaMessage)}
     * will send to the remote recipient immediately (though after the queue has been processed)
     *
     * If this method is called multiple times it will not result in several subscriptions.
     * If the method is called a second time with the same Recipient then nothing changes.
     * If the method is called a second time with a different recipient then messages will no longer be
     * sent to the first recipient; all messages will be routed to the second recipient.
     *
     * @param recipient
     *              The Recipient who the messages will be sent to.
     */
    /* package */ void init(final Recipient recipient) {
        if (recipient == this.recipient) {
            LogUtil.print(getClass(), "Suppressing a double subscription");
            return;
        }

        if (this.recipient != null) {
            LogUtil.print(getClass(), "Subscribing to a different recipient, so clearing previous subscriptions. Was this intentional?");
            this.clear();
        }

        this.recipient = recipient;
        attachMessagesReadyForSendingSubscriber();
        processPreInitMessagesQueue();
    }

    private void attachMessagesReadyForSendingSubscriber() {
        final Subscription subscription =
                this.messagesReadyForSending
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        this::sendAndSaveMessage,
                        this::handleSendingMessageError
                );

        this.subscriptions.add(subscription);
    }

    private void sendAndSaveMessage(final SofaMessage outgoingSofaMessage) {
        if (this.recipient.isGroup()) {
            sendAndSaveGroupMessage(outgoingSofaMessage);
        } else {
            sendAndSaveUserMessage(outgoingSofaMessage);
        }

    }

    private void sendAndSaveGroupMessage(final SofaMessage outgoingSofaMessage) {
        // Todo - send to group
    }

    private void sendAndSaveUserMessage(final SofaMessage outgoingSofaMessage) {
        BaseApplication
                .get()
                .getSofaMessageManager()
                .sendAndSaveMessage(this.recipient.getUser(), outgoingSofaMessage);
    }

    private void handleSendingMessageError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error during sending message", throwable);
    }

    private void processPreInitMessagesQueue() {
        final Subscription subscription =
                Observable
                .from(this.preInitMessagesQueue)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .doOnCompleted(this.preInitMessagesQueue::clear)
                .subscribe(
                        this.messagesReadyForSending::onNext,
                        this::handleMessageQueueError
                );

        this.subscriptions.add(subscription);
    }

    private void handleMessageQueueError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error during processing message queue", throwable);
    }
}
