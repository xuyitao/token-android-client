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

package com.tokenbrowser.manager.chat;


import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.tokenbrowser.BuildConfig;
import com.tokenbrowser.crypto.HDWallet;
import com.tokenbrowser.crypto.signal.model.DecryptedSignalMessage;
import com.tokenbrowser.crypto.signal.store.ProtocolStore;
import com.tokenbrowser.manager.store.ConversationStore;
import com.tokenbrowser.model.local.Group;
import com.tokenbrowser.model.local.SendState;
import com.tokenbrowser.model.local.User;
import com.tokenbrowser.model.sofa.Init;
import com.tokenbrowser.model.sofa.InitRequest;
import com.tokenbrowser.model.sofa.PaymentRequest;
import com.tokenbrowser.model.sofa.SofaAdapters;
import com.tokenbrowser.model.sofa.SofaMessage;
import com.tokenbrowser.model.sofa.SofaType;
import com.tokenbrowser.util.FileUtil;
import com.tokenbrowser.util.LogUtil;
import com.tokenbrowser.view.BaseApplication;
import com.tokenbrowser.view.notification.ChatNotificationManager;

import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessagePipe;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.SignalServiceUrl;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import rx.Single;

public class SofaMessageReceiver {

    private final static String USER_AGENT = "Android " + BuildConfig.APPLICATION_ID + " - " + BuildConfig.VERSION_NAME +  ":" + BuildConfig.VERSION_CODE;

    private final ConversationStore conversationStore;
    private final ProtocolStore protocolStore;
    private final SignalServiceMessageReceiver messageReceiver;
    private final HDWallet wallet;

    private SignalServiceMessagePipe messagePipe;
    private boolean isReceivingMessages;

    public SofaMessageReceiver(@NonNull final HDWallet wallet,
                               @NonNull final ProtocolStore protocolStore,
                               @NonNull final ConversationStore conversationStore,
                               @NonNull final SignalServiceUrl[] urls) {
        this.wallet = wallet;
        this.protocolStore = protocolStore;
        this.conversationStore = conversationStore;

        this.messageReceiver =
                new SignalServiceMessageReceiver(
                        urls,
                        this.wallet.getOwnerAddress(),
                        this.protocolStore.getPassword(),
                        this.protocolStore.getSignalingKey(),
                        USER_AGENT);
    }

    public void receiveMessagesAsync() {
        if (this.isReceivingMessages) {
            // Already running.
            return;
        }

        this.isReceivingMessages = true;
        new Thread(() -> {
            while (isReceivingMessages) {
                try {
                    final DecryptedSignalMessage signalMessage = fetchLatestMessage();
                    ChatNotificationManager.showNotification(signalMessage);
                } catch (TimeoutException e) {
                    // Nop -- this is expected to happen
                }
            }
        }).start();
    }

    public DecryptedSignalMessage fetchLatestMessage() throws TimeoutException {
        if (this.messagePipe == null) {
            this.messagePipe = messageReceiver.createMessagePipe();
        }

        try {
            final SignalServiceEnvelope envelope = messagePipe.read(10, TimeUnit.SECONDS);
            return decryptIncomingSignalServiceEnvelope(envelope);
        } catch (final TimeoutException ex) {
            throw new TimeoutException(ex.getMessage());
        } catch (final IllegalStateException | InvalidKeyException | InvalidKeyIdException | DuplicateMessageException | InvalidVersionException | LegacyMessageException | InvalidMessageException | NoSessionException | org.whispersystems.libsignal.UntrustedIdentityException | IOException e) {
            LogUtil.exception(getClass(), "Error while fetching latest message", e);
        }
        return null;
    }

    private DecryptedSignalMessage decryptIncomingSignalServiceEnvelope(final SignalServiceEnvelope envelope) throws InvalidVersionException, InvalidMessageException, InvalidKeyException, DuplicateMessageException, InvalidKeyIdException, org.whispersystems.libsignal.UntrustedIdentityException, LegacyMessageException, NoSessionException {
        // ToDo -- When do we need to create new keys?
 /*       if (envelope.getType() == SignalServiceProtos.Envelope.Type.PREKEY_BUNDLE_VALUE) {
            // New keys need to be registered with the server.
            registerWithServer();
            return;
        }*/
        return handleIncomingSofaMessage(envelope);
    }

    private DecryptedSignalMessage handleIncomingSofaMessage(final SignalServiceEnvelope envelope) throws InvalidVersionException, InvalidMessageException, InvalidKeyException, DuplicateMessageException, InvalidKeyIdException, org.whispersystems.libsignal.UntrustedIdentityException, LegacyMessageException, NoSessionException {
        final SignalServiceAddress localAddress = new SignalServiceAddress(this.wallet.getOwnerAddress());
        final SignalServiceCipher cipher = new SignalServiceCipher(localAddress, this.protocolStore);
        final SignalServiceContent content = cipher.decrypt(envelope);
        final String messageSource = envelope.getSource();

        if (isUserBlocked(messageSource)) {
            LogUtil.i(getClass(), "A blocked user is trying to send a message");
            return null;
        }

        if (content.getDataMessage().isPresent()) {
            final SignalServiceDataMessage dataMessage = content.getDataMessage().get();
            if (dataMessage.isGroupUpdate()) return handleGroupMessage(dataMessage);
            else return handleTextMessage(messageSource, dataMessage);
        }
        return null;
    }

    @NonNull
    private DecryptedSignalMessage handleTextMessage(final String messageSource, final SignalServiceDataMessage dataMessage) {
        final Optional<String> messageBody = dataMessage.getBody();
        final Optional<List<SignalServiceAttachment>> attachments = dataMessage.getAttachments();
        final DecryptedSignalMessage decryptedMessage = new DecryptedSignalMessage(messageSource, messageBody.get(), attachments);

        saveIncomingMessageToDatabase(decryptedMessage);
        return decryptedMessage;
    }

    private DecryptedSignalMessage handleGroupMessage(final SignalServiceDataMessage dataMessage) {
        final SignalServiceGroup signalGroup = dataMessage.getGroupInfo().get();
        new Group()
                .initFromSignalGroup(signalGroup)
                .subscribe(
                        this.conversationStore::saveNewGroup,
                        ex -> LogUtil.e(getClass(), "Error creating incoming group. " + ex)
                );
        return null;
    }

    private boolean isUserBlocked(final String address) {
        return BaseApplication
                .get()
                .getRecipientManager()
                .isUserBlocked(address)
                .toBlocking()
                .value();
    }

    private void saveIncomingMessageToDatabase(final DecryptedSignalMessage signalMessage) {
        if (signalMessage == null || signalMessage.getBody() == null || signalMessage.getSource() == null) {
            LogUtil.w(getClass(), "Attempt to save invalid DecryptedSignalMessage to database.");
            return;
        }

        processAttachments(signalMessage);

        BaseApplication
                .get()
                .getRecipientManager()
                .getUserFromTokenId(signalMessage.getSource())
                .subscribe(
                        (user) -> this.saveIncomingMessageFromUserToDatabase(user, signalMessage),
                        ex -> LogUtil.e(getClass(), "Error getting user. " + ex)
                );
    }

    private void processAttachments(final DecryptedSignalMessage signalMessage) {
        if (!signalMessage.getAttachments().isPresent()) {
            return;
        }

        final List<SignalServiceAttachment> attachments = signalMessage.getAttachments().get();
        if (attachments.size() > 0) {
            final SignalServiceAttachment attachment = attachments.get(0);
            final String filePath = saveAttachmentToFile(attachment.asPointer());
            signalMessage.setAttachmentFilePath(filePath);
        }
    }

    private @Nullable
    String saveAttachmentToFile(final SignalServiceAttachmentPointer attachment) {
        final FileUtil fileUtil = new FileUtil();
        final File attachmentFile = fileUtil.writeAttachmentToFileFromMessageReceiver(attachment, this.messageReceiver);
        return attachmentFile != null ? attachmentFile.getAbsolutePath() : null;
    }

    private void saveIncomingMessageFromUserToDatabase(final User user, final DecryptedSignalMessage signalMessage) {
        final SofaMessage remoteMessage = new SofaMessage()
                .makeNew(user, signalMessage.getBody())
                .setAttachmentFilePath(signalMessage.getAttachmentFilePath())
                .setSendState(SendState.STATE_RECEIVED);
        if (remoteMessage.getType() == SofaType.PAYMENT) {
            // Don't render incoming SOFA::Payments,
            // but ensure we have the sender cached.
            fetchAndCacheIncomingPaymentSender(user);
            return;
        } else if(remoteMessage.getType() == SofaType.PAYMENT_REQUEST) {
            generatePayloadWithLocalAmountEmbedded(remoteMessage)
                    .subscribe((updatedPayload) -> {
                                remoteMessage.setPayload(updatedPayload);
                                this.conversationStore.saveNewMessage(user, remoteMessage);
                            },
                            this::handleError);
            return;
        } else if (remoteMessage.getType() == SofaType.INIT_REQUEST) {
            // Don't render initRequests,
            // but respond to them.
            respondToInitRequest(user, remoteMessage);
            return;
        }

        this.conversationStore.saveNewMessage(user, remoteMessage);
    }

    private void handleError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error while generating payload with local amount embedded", throwable);
    }

    private void respondToInitRequest(final User sender, final SofaMessage remoteMessage) {
        try {
            final InitRequest initRequest = SofaAdapters.get().initRequestFrom(remoteMessage.getPayload());
            final Init initMessage = new Init().construct(initRequest, this.wallet.getPaymentAddress());
            final String payload = SofaAdapters.get().toJson(initMessage);
            final SofaMessage newSofaMessage = new SofaMessage().makeNew(sender, payload);

            BaseApplication
                    .get()
                    .getSofaMessageManager()
                    .sendMessage(sender, newSofaMessage);
        } catch (final IOException e) {
            LogUtil.exception(getClass(), "Failed to respond to incoming init request", e);
        }
    }

    private void fetchAndCacheIncomingPaymentSender(final User sender) {
        BaseApplication
                .get()
                .getRecipientManager()
                .getUserFromTokenId(sender.getTokenId());
    }

    private Single<String> generatePayloadWithLocalAmountEmbedded(final SofaMessage remoteMessage) {
        try {
            final PaymentRequest request = SofaAdapters.get().txRequestFrom(remoteMessage.getPayload());
            return request
                    .generateLocalPrice()
                    .map((updatedPaymentRequest) -> SofaAdapters.get().toJson(updatedPaymentRequest));
        } catch (final IOException ex) {
            LogUtil.exception(getClass(), "Unable to embed local price", ex);
        }

        return Single.just(remoteMessage.getPayloadWithHeaders());
    }

    public void shutdown() {
        this.isReceivingMessages = false;
        if (this.messagePipe != null) {
            this.messagePipe.shutdown();
            this.messagePipe = null;
        }
    }
}
