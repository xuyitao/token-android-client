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

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Toast;

import com.tokenbrowser.R;
import com.tokenbrowser.crypto.HDWallet;
import com.tokenbrowser.exception.PermissionException;
import com.tokenbrowser.model.local.ActivityResultHolder;
import com.tokenbrowser.model.local.Conversation;
import com.tokenbrowser.model.local.Group;
import com.tokenbrowser.model.local.PermissionResultHolder;
import com.tokenbrowser.model.local.Recipient;
import com.tokenbrowser.model.local.User;
import com.tokenbrowser.model.sofa.Command;
import com.tokenbrowser.model.sofa.Control;
import com.tokenbrowser.model.sofa.Message;
import com.tokenbrowser.model.sofa.PaymentRequest;
import com.tokenbrowser.model.sofa.SofaAdapters;
import com.tokenbrowser.model.sofa.SofaMessage;
import com.tokenbrowser.presenter.AmountPresenter;
import com.tokenbrowser.presenter.Presenter;
import com.tokenbrowser.util.ChatNavigation;
import com.tokenbrowser.util.FileUtil;
import com.tokenbrowser.util.ImageUtil;
import com.tokenbrowser.util.KeyboardUtil;
import com.tokenbrowser.util.LogUtil;
import com.tokenbrowser.util.OnSingleClickListener;
import com.tokenbrowser.util.PaymentType;
import com.tokenbrowser.util.PermissionUtil;
import com.tokenbrowser.util.SoundManager;
import com.tokenbrowser.view.Animation.SlideUpAnimator;
import com.tokenbrowser.view.BaseApplication;
import com.tokenbrowser.view.activity.AttachmentConfirmationActivity;
import com.tokenbrowser.view.activity.ChatActivity;
import com.tokenbrowser.view.adapter.MessageAdapter;
import com.tokenbrowser.view.custom.ChatInputView;
import com.tokenbrowser.view.custom.SpeedyLinearLayoutManager;
import com.tokenbrowser.view.notification.ChatNotificationManager;

import java.io.File;
import java.io.IOException;

import rx.Single;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

import static com.tokenbrowser.model.local.Group.GROUP_ID_LENGTH;


public final class ChatPresenter implements Presenter<ChatActivity> {

    private static final int REQUEST_RESULT_CODE = 1;
    private static final int PAY_RESULT_CODE = 2;
    private static final int PICK_ATTACHMENT = 3;
    private static final int CAPTURE_IMAGE = 4;
    private static final int CONFIRM_ATTACHMENT = 5;
    private static final String CAPTURE_FILENAME = "caputureImageFilename";

    private boolean firstViewAttachment = true;
    private boolean isConversationLoaded = false;
    private ChatActivity activity;
    private ChatNavigation chatNavigation;
    private CompositeSubscription subscriptions;
    private HDWallet userWallet;
    private int lastVisibleMessagePosition;
    private OutgoingMessageQueue outgoingMessageQueue;
    private MessageAdapter messageAdapter;
    private Pair<PublishSubject<SofaMessage>, PublishSubject<SofaMessage>> chatObservables;
    private PendingTransactionsObservable pendingTransactionsObservable;
    private Recipient recipient;
    private SpeedyLinearLayoutManager layoutManager;
    private String captureImageFilename;
    private Subscription newMessageSubscription;
    private Subscription updatedMessageSubscription;

    @Override
    public void onViewAttached(final ChatActivity activity) {
        this.activity = activity;

        if (this.firstViewAttachment) {
            this.firstViewAttachment = false;
            initLongLivingObjects();
        }
        initShortLivingObjects();
    }

    private void initLongLivingObjects() {
        this.subscriptions = new CompositeSubscription();
        this.outgoingMessageQueue = new OutgoingMessageQueue();
        this.pendingTransactionsObservable = new PendingTransactionsObservable();
        initMessageAdapter();
    }

    private void initMessageAdapter() {
        this.messageAdapter = new MessageAdapter()
                .addOnPaymentRequestApproveListener(message -> updatePaymentRequestState(message, PaymentRequest.ACCEPTED))
                .addOnPaymentRequestRejectListener(message -> updatePaymentRequestState(message, PaymentRequest.REJECTED))
                .addOnUsernameClickListener(this::handleUsernameClicked)
                .addOnImageClickListener(this::handleImageClicked)
                .addOnFileClickListener(path -> this.chatNavigation.startAttachmentPicker(this.activity, path));
    }

    private void handleUsernameClicked(final String username) {
        final Subscription sub =
                BaseApplication
                .get()
                .getRecipientManager()
                .getUserFromUsername(username)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        user -> handleUsernameClicked(username, user),
                        ex   -> handleUsernameClicked(username, null)
                );

        this.subscriptions.add(sub);
    }

    private void handleImageClicked(final String filePath) {
        this.chatNavigation.startImageActivity(this.activity, filePath);
    }

    private void updatePaymentRequestState(
            final SofaMessage existingMessage,
            final @PaymentRequest.State int newState) {

        final Subscription sub =
                getRecipient()
                .subscribe(recipient ->
                        handleUpdatePaymentRequestState(recipient, existingMessage, newState),
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private void handleUpdatePaymentRequestState(final Recipient recipient,
                                                 final SofaMessage existingMessage,
                                                 final @PaymentRequest.State int newState) {
        // Todo - Handle groups
        BaseApplication
                .get()
                .getTransactionManager()
                .updatePaymentRequestState(recipient.getUser(), existingMessage, newState);
    }
    
    private void handleUsernameClicked(final String searchedForUsername, final User user) {
        if (this.activity == null) return;
        if (user == null) {
            Toast.makeText(this.activity, this.activity.getString(R.string.username_search_response_no_match), Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean isSameUser = user.getUsernameForEditing().equals(searchedForUsername);

        if (!isSameUser) {
            Toast.makeText(this.activity, this.activity.getString(R.string.username_search_response_no_match), Toast.LENGTH_SHORT).show();
            return;
        }

        viewProfile(user.getTokenId());
    }

    private void handleUpdatedMessage(final SofaMessage sofaMessage) {
        this.messageAdapter.updateMessage(sofaMessage);
    }

    private void initShortLivingObjects() {
        initChatNavigation();
        getWallet();
        initClickListeners();
        initLayoutManager();
        initAdapterAnimation();
        initRecyclerView();
        initControlView();
        loadOrUseRecipient();
        initLoadingSpinner();
    }

    private void initChatNavigation() {
        this.chatNavigation = new ChatNavigation();
    }

    private void getWallet() {
        final Subscription walletSub =
                BaseApplication.get()
                .getTokenManager()
                .getWallet()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        wallet -> this.userWallet = wallet,
                        this::handleError
                );

        this.subscriptions.add(walletSub);
    }

    private void initLayoutManager() {
        if (this.activity == null) return;
        this.layoutManager = new SpeedyLinearLayoutManager(this.activity);
        this.activity.getBinding().messagesList.setLayoutManager(this.layoutManager);
    }

    private void initAdapterAnimation() {
        final SlideUpAnimator anim = Build.VERSION.SDK_INT >= 21
                ? new SlideUpAnimator(new PathInterpolator(0.33f, 0.78f, 0.3f, 1))
                : new SlideUpAnimator(new DecelerateInterpolator());
        anim.setAddDuration(400);
        this.activity.getBinding().messagesList.setItemAnimator(anim);
    }

    private void initRecyclerView() {
        if (this.activity == null) return;
        attachMessageAdapter();

        // Hack to scroll to bottom when keyboard rendered
        this.activity.getBinding().messagesList.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> handleLayoutChanged(bottom, oldBottom));
        this.activity.getBinding().messagesList.getLayoutManager().scrollToPosition(this.lastVisibleMessagePosition);
    }

    private void handleLayoutChanged(final int bottom,
                                     final int oldBottom) {
        if (this.activity == null || this.messageAdapter.getItemCount() <= 0) return;
        if (bottom < oldBottom) {
            final int bottomPosition = this.messageAdapter.getItemCount() - 1;
            this.activity.getBinding().messagesList.postDelayed(() -> this.smoothScrollToPosition(bottomPosition), 100);
        }
    }

    private void attachMessageAdapter() {
        if (this.messageAdapter == null) return;
        this.messageAdapter.notifyDataSetChanged();
        this.activity.getBinding().messagesList.setAdapter(this.messageAdapter);
    }

    private void updateEmptyState() {
        // Hide empty state if we have some content
        final boolean showingEmptyState = this.activity.getBinding().emptyStateSwitcher.getCurrentView().getId() == this.activity.getBinding().emptyState.getId();
        final boolean shouldShowEmptyState = this.messageAdapter.getItemCount() == 0;

        if (shouldShowEmptyState && !showingEmptyState) {
            this.activity.getBinding().emptyStateSwitcher.showPrevious();
        } else if (!shouldShowEmptyState && showingEmptyState) {
            this.activity.getBinding().emptyStateSwitcher.showNext();
        }
    }

    private void initClickListeners() {
        this.activity.getBinding().chatInput
                .setOnSendMessageClicked(this::sendMessage)
                .setOnAttachmentClicked(__ -> checkExternalStoragePermission())
                .setOnCameraClickedListener(__ -> checkCameraPermission());
        this.activity.getBinding().balanceBar.setOnRequestClicked(this.requestButtonClicked);
        this.activity.getBinding().balanceBar.setOnPayClicked(this.payButtonClicked);
        this.activity.getBinding().controlView.setOnControlClickedListener(this::handleControlClicked);
    }

    private void sendMessage(final String userInput) {
        final Message message = new Message().setBody(userInput);
        final String messageBody = SofaAdapters.get().toJson(message);
        final SofaMessage sofaMessage = new SofaMessage().makeNew(getCurrentLocalUser(), messageBody);
        this.outgoingMessageQueue.send(sofaMessage);
    }

    private void checkExternalStoragePermission() {
        PermissionUtil.hasPermission(
                this.activity,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                PermissionUtil.READ_EXTERNAL_STORAGE_PERMISSION,
                this::startAttachmentActivity
        );
    }

    private void checkCameraPermission() {
        PermissionUtil.hasPermission(
                this.activity,
                Manifest.permission.CAMERA,
                PermissionUtil.CAMERA_PERMISSION,
                this::startCameraActivity
        );
    }

    private void startCameraActivity() {
        final FileUtil fileUtil = new FileUtil();
        final File photoFile = fileUtil.createImageFileWithRandomName();
        this.captureImageFilename = photoFile.getName();
        final Uri photoUri = fileUtil.getUriFromFile(photoFile);

        this.chatNavigation.startCameraActivity(this.activity, photoUri, CAPTURE_IMAGE);
    }

    private final OnSingleClickListener requestButtonClicked = new OnSingleClickListener() {
        @Override
        public void onSingleClick(final View v) {
            chatNavigation.startPaymentRequestActivityForResult(activity, REQUEST_RESULT_CODE);
        }
    };

    private final OnSingleClickListener payButtonClicked = new OnSingleClickListener() {
        @Override
        public void onSingleClick(final View v) {
            chatNavigation.startPaymentActivityForResult(activity, PAY_RESULT_CODE);
        }
    };

    private void handleControlClicked(final Control control) {
        this.activity.getBinding().controlView.hideView();
        removePadding();
        if (control.hasAction()) {
            this.chatNavigation.startWebViewActivity(this.activity, control.getActionUrl());
        } else {
            sendCommandMessage(control);
        }
    }

    private void removePadding() {
        final int paddingRight = this.activity.getBinding().messagesList.getPaddingRight();
        final int paddingLeft = this.activity.getBinding().messagesList.getPaddingLeft();
        final int paddingBottom = this.activity.getResources().getDimensionPixelSize(R.dimen.message_list_bottom_padding);
        this.activity.getBinding().messagesList.setPadding(paddingLeft, 0 , paddingRight, paddingBottom);
    }

    private void sendCommandMessage(final Control control) {
        final Command command = new Command()
                .setBody(control.getLabel())
                .setValue(control.getValue());
        final String commandPayload = SofaAdapters.get().toJson(command);
        final SofaMessage sofaMessage = new SofaMessage().makeNew(getCurrentLocalUser(), commandPayload);
        this.outgoingMessageQueue.send(sofaMessage);
    }

    private void initControlView() {
        this.activity.getBinding().controlView.setOnSizeChangedListener(this::setPadding);
    }

    private void setPadding(final int height) {
        if (this.activity == null) return;
        final int paddingBottom = this.activity.getResources().getDimensionPixelSize(R.dimen.chat_button_padding) + height;
        final int paddingRight = this.activity.getBinding().messagesList.getPaddingRight();
        final int paddingLeft = this.activity.getBinding().messagesList.getPaddingLeft();
        this.activity.getBinding().messagesList.setPadding(paddingLeft, 0, paddingRight, paddingBottom);
        this.activity.getBinding().messagesList.scrollToPosition(this.messageAdapter.getItemCount() - 1);
    }

    private void loadOrUseRecipient() {
        if (this.recipient == null) {
            loadRecipient();
            return;
        }

        updateUiFromRemoteUser();
        processPaymentFromIntent();
        this.outgoingMessageQueue.init(this.recipient);
        initPendingTransactionsObservable();
    }

    private void initPendingTransactionsObservable() {
        // Todo - handle groups
        if (this.recipient.isGroup()) return;

        final Subscription subscription =
                this.pendingTransactionsObservable
                    .init(this.recipient.getUser())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            pendingTransaction -> handleUpdatedMessage(pendingTransaction.getSofaMessage()),
                            this::handleError
                    );

        this.subscriptions.add(subscription);
    }

    private void loadRecipient() {
        final String threadId = this.activity.getIntent().getStringExtra(ChatActivity.EXTRA__THREAD_ID);
        if (isGroup(threadId)) {
            loadGroupRecipient(threadId);
        } else {
            loadUserRecipient(threadId);
        }
    }

    private void loadGroupRecipient(final String groupId) {
        final Subscription sub = BaseApplication
                .get()
                .getRecipientManager()
                .getGroupFromId(groupId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleGroupLoaded,
                        this::handleRecipientLoadFailed
                );

        this.subscriptions.add(sub);
    }

    private void loadUserRecipient(final String tokenId) {
        final Subscription sub = BaseApplication
                .get()
                .getRecipientManager()
                .getUserFromTokenId(tokenId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleUserLoaded,
                        this::handleRecipientLoadFailed
                );

        this.subscriptions.add(sub);
    }

    private boolean isGroup(final String threadId) {
        // Todo - check compatability with other clients (i.e. iOS)
        return threadId.length() == GROUP_ID_LENGTH;
    }

    private void handleGroupLoaded(final Group group) {
        handleRecipientLoaded(new Recipient(group));
    }

    private void handleUserLoaded(final User user) {
        handleRecipientLoaded(new Recipient(user));
    }

    private void handleRecipientLoaded(final Recipient recipient) {
        this.recipient = recipient;
        if (this.recipient != null) {
            if (shouldPlayScanSounds()) SoundManager.getInstance().playSound(SoundManager.FOUND_USER);
            loadOrUseRecipient();
        }
    }

    private void handleRecipientLoadFailed(final Throwable throwable) {
        Toast.makeText(BaseApplication.get(), R.string.error__app_loading, Toast.LENGTH_LONG).show();
        if (this.activity != null) {
            if (shouldPlayScanSounds()) SoundManager.getInstance().playSound(SoundManager.SCAN_ERROR);
            this.activity.finish();
        }
    }

    private boolean shouldPlayScanSounds() {
        return this.activity != null
            && this.activity.getIntent() != null
            && this.activity.getIntent().getBooleanExtra(ChatActivity.EXTRA__PLAY_SCAN_SOUNDS, false);
    }

    private void updateUiFromRemoteUser() {
        initToolbar();
        initChatMessageStore();
        initLoadingSpinner();
    }

    private void processPaymentFromIntent() {
        if (this.recipient == null) return;

        final String value = this.activity.getIntent().getStringExtra(ChatActivity.EXTRA__ETH_AMOUNT);
        final int paymentAction = this.activity.getIntent().getIntExtra(ChatActivity.EXTRA__PAYMENT_ACTION, 0);

        this.activity.getIntent().removeExtra(ChatActivity.EXTRA__ETH_AMOUNT);
        this.activity.getIntent().removeExtra(ChatActivity.EXTRA__PAYMENT_ACTION);

        if (value == null || paymentAction == 0) {
            return;
        }

        if (paymentAction == PaymentType.TYPE_SEND) {
            sendPaymentWithValue(value);
        } else if (paymentAction == PaymentType.TYPE_REQUEST) {
            sendPaymentRequestWithValue(value);
        }
    }

    private void sendPaymentWithValue(final String value) {
        final Subscription sub =
                getRecipient()
                .subscribe(
                        recipient -> sendPayment(recipient, value),
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private void sendPayment(final Recipient recipient, final String value) {
        // Todo - handle groups
        BaseApplication.get()
                .getTransactionManager()
                .sendPayment(recipient.getUser(), value);
    }

    private void sendPaymentRequestWithValue(final String value) {
        new PaymentRequest()
                .setDestinationAddress(this.userWallet.getPaymentAddress())
                .setValue(value)
                .generateLocalPrice()
                .subscribe(
                        this::sendPaymentRequest,
                        this::handleError
                );
    }

    private void sendPaymentRequest(final PaymentRequest request) {
        final String messageBody = SofaAdapters.get().toJson(request);
        final SofaMessage message = new SofaMessage().makeNew(getCurrentLocalUser(), messageBody);
        this.outgoingMessageQueue.send(message);
    }

    private void initLoadingSpinner() {
        if (this.activity == null) return;
        this.activity.getBinding().loadingViewContainer.setVisibility(this.recipient == null ? View.VISIBLE : View.GONE);
        if (this.recipient == null) {
            final Animation rotateAnimation = AnimationUtils.loadAnimation(this.activity, R.anim.rotate);
            this.activity.getBinding().loadingView.startAnimation(rotateAnimation);
        } else {
            this.activity.getBinding().loadingView.clearAnimation();
        }
    }

    private void initToolbar() {
        this.activity.getBinding().title.setText(this.recipient.getDisplayName());
        this.activity.getBinding().closeButton.setOnClickListener(this::handleBackButtonClicked);
        this.activity.getBinding().avatar.setOnClickListener(__ -> viewRemoteUserProfile());
        ImageUtil.load(this.recipient.getAvatar(), this.activity.getBinding().avatar);
    }

    private void handleBackButtonClicked(final View v) {
        hideKeyboard();
        this.activity.onBackPressed();
    }

    private void viewRemoteUserProfile() {
        // Todo -- handle groups?
        final Subscription sub =
                getRecipient()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        remoteUser -> viewProfile(recipient.getThreadId()),
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private void viewProfile(final String ownerAddress) {
        this.chatNavigation.startProfileActivity(this.activity, ownerAddress);
    }

    private void initChatMessageStore() {
        ChatNotificationManager.suppressNotificationsForConversation(this.recipient.getThreadId());

        this.chatObservables =
                BaseApplication
                .get()
                .getSofaMessageManager()
                .registerForConversationChanges(this.recipient.getThreadId());

        final Subscription conversationLoadedSub =
                BaseApplication
                .get()
                .getSofaMessageManager()
                .loadConversation(this.recipient.getThreadId())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleConversationLoaded,
                        this::handleError);

        this.subscriptions.add(conversationLoadedSub);
    }

    private void handleConversationLoaded(final Conversation conversation) {
        initConversation(conversation);
        updateEmptyState();
        tryClearMessageSubscriptions();
        initMessageObservables();
        this.isConversationLoaded = true;
    }

    private void initConversation(final Conversation conversation) {
        initConversationRecipient();
        initConversationMessages(conversation);
    }

    private void initConversationRecipient() {
        this.messageAdapter.setRecipient(this.recipient);
    }

    private void initConversationMessages(final Conversation conversation) {
        final boolean shouldAddMessages = conversation != null && conversation.getAllMessages() != null && conversation.getAllMessages().size() > 0;
        if (shouldAddMessages) {
            this.messageAdapter.setMessages(conversation.getAllMessages());
            scrollToPosition(getSafePosition());

            final SofaMessage lastSofaMessage = conversation.getAllMessages().get(conversation.getAllMessages().size() - 1);
            setControlView(lastSofaMessage);
        } else {
            tryInitAppConversation();
        }
    }

    private void tryInitAppConversation() {
        if (this.recipient.isGroup() || !this.recipient.getUser().isApp()) return;

        final Message message = new Message().setBody("");
        final String messageBody = SofaAdapters.get().toJson(message);
        final SofaMessage sofaMessage = new SofaMessage().makeNew(getCurrentLocalUser(), messageBody);

        BaseApplication
                .get()
                .getSofaMessageManager()
                .sendMessage(this.recipient.getUser(), sofaMessage);
    }

    private void initMessageObservables() {
        this.newMessageSubscription =
                chatObservables.first
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleNewMessage,
                        this::handleError
                );

        this.updatedMessageSubscription =
                chatObservables.second
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleUpdatedMessage,
                        this::handleError
                );

        this.subscriptions.addAll(
                this.newMessageSubscription,
                this.updatedMessageSubscription
        );
    }

    private void tryClearMessageSubscriptions() {
        if (this.newMessageSubscription != null) {
            this.newMessageSubscription.unsubscribe();
        }

        if (this.updatedMessageSubscription != null) {
            this.updatedMessageSubscription.unsubscribe();
        }
    }

    private void handleNewMessage(final SofaMessage sofaMessage) {
        setControlView(sofaMessage);
        this.messageAdapter.updateMessage(sofaMessage);
        updateEmptyState();
        tryScrollToBottom(true);
        playNewMessageSound(sofaMessage.isSentBy(getCurrentLocalUser()));
        handleKeyboardVisibility(sofaMessage);
    }

    private void tryScrollToBottom(final boolean animate) {
        if (this.activity == null || this.layoutManager == null || this.messageAdapter.getItemCount() == 0) return;
        // Only animate if we're already near the bottom
        if (this.layoutManager.findLastVisibleItemPosition() < this.messageAdapter.getItemCount() - 3) return;

        final int bottomPosition = this.messageAdapter.getItemCount() - 1;
        if (animate) {
            smoothScrollToPosition(bottomPosition);
        } else {
            scrollToPosition(bottomPosition);
        }
    }

    private void playNewMessageSound(final boolean sentByLocal) {
        if (sentByLocal) {
            SoundManager.getInstance().playSound(SoundManager.SEND_MESSAGE);
        } else {
            SoundManager.getInstance().playSound(SoundManager.RECEIVE_MESSAGE);
        }
    }

    private void handleKeyboardVisibility(final SofaMessage sofaMessage) {
        final boolean viewIsNull = this.activity == null || this.activity.getBinding().chatInput == null;
        if (viewIsNull || sofaMessage.isSentBy(getCurrentLocalUser())) return;

        try {
            final Message message = SofaAdapters.get().messageFrom(sofaMessage.getPayload());
            if (message.shouldHideKeyboard()) {
                hideKeyboard();
            }

        } catch (IOException e) {
            LogUtil.exception(getClass(), "Error during handling visibility of keyboard", e);
        }
    }

    private void hideKeyboard() {
        if (this.activity == null) return;
        final ChatInputView chatInputView = this.activity.getBinding().chatInput;
        if (chatInputView == null || chatInputView.getInputView() == null) return;
        KeyboardUtil.hideKeyboard(this.activity.getBinding().chatInput.getInputView());
    }

    private void setControlView(final SofaMessage sofaMessage) {
        if (sofaMessage == null || TextUtils.isEmpty(sofaMessage.getPayload()) || this.activity == null) {
            return;
        }

        try {
            final Message message = SofaAdapters.get().messageFrom(sofaMessage.getPayload());
            final boolean notNullAndNotZero = message.getControls() != null && message.getControls().size() > 0;
            this.activity.getBinding().controlView.hideView();

            if (notNullAndNotZero) {
                this.activity.getBinding().controlView.showControls(message.getControls());
            } else {
                removePadding();
            }
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    // Returns last known scroll position, or last position if unknown
    private int getSafePosition() {
        if (this.lastVisibleMessagePosition > 0) return this.lastVisibleMessagePosition;
        if (this.messageAdapter.getItemCount() - 1 > 0) return this.messageAdapter.getItemCount() - 1;
        return 0;
    }

    private void smoothScrollToPosition(final int position) {
        if (this.activity == null) return;
        this.activity.getBinding().messagesList.smoothScrollToPosition(position);
    }

    private void scrollToPosition(final int position) {
        if (this.activity == null) return;
        this.activity.getBinding().messagesList.scrollToPosition(position);
    }

    public boolean handleActivityResult(final ActivityResultHolder resultHolder) {
        if (resultHolder.getResultCode() != Activity.RESULT_OK || this.activity == null) {
            return false;
        }

        if (resultHolder.getRequestCode() == REQUEST_RESULT_CODE) {
            final String value = resultHolder.getIntent().getStringExtra(AmountPresenter.INTENT_EXTRA__ETH_AMOUNT);
            sendPaymentRequestWithValue(value);
        } else if(resultHolder.getRequestCode() == PAY_RESULT_CODE) {
            final String value = resultHolder.getIntent().getStringExtra(AmountPresenter.INTENT_EXTRA__ETH_AMOUNT);
            sendPaymentWithValue(value);
        } else if (resultHolder.getRequestCode() == PICK_ATTACHMENT) {
            handleAttachmentResult(resultHolder);
        } else if (resultHolder.getRequestCode() == CAPTURE_IMAGE) {
            handleCameraResult();
        } else if (resultHolder.getRequestCode() == CONFIRM_ATTACHMENT) {
            handleConfirmAttachmentResult(resultHolder);
        }

        return true;
    }

    /**
     *
     * @param permissionResultHolder Object containing info about the permission action
     * @return a boolean that tells if the method has handled the permission result
     * @throws PermissionException
     */
    public boolean tryHandlePermissionResult(final PermissionResultHolder permissionResultHolder) throws PermissionException {
        if (permissionResultHolder == null || this.activity == null) return false;
        final int[] grantResults = permissionResultHolder.getGrantResults();

        // Return true so the calling class knows the permission is handled
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) return true;

        if (permissionResultHolder.getRequestCode() == PermissionUtil.CAMERA_PERMISSION) {
            startCameraActivity();
            return true;
        } else if (permissionResultHolder.getRequestCode() == PermissionUtil.READ_EXTERNAL_STORAGE_PERMISSION) {
            startAttachmentActivity();
            return true;
        } else {
            throw new PermissionException("This permission doesn't belong in this context");
        }
    }

    private void startAttachmentActivity() {
        this.chatNavigation.startAttachmentActivity(this.activity, PICK_ATTACHMENT);
    }

    private void handleAttachmentResult(final ActivityResultHolder resultHolder) {
        this.chatNavigation.startAttachmentConfirmationActivity(this.activity, resultHolder.getIntent().getData(), CONFIRM_ATTACHMENT);
    }

    private void handleConfirmAttachmentResult(final ActivityResultHolder resultHolder) {
        final String filePath = resultHolder.getIntent().getStringExtra(AttachmentConfirmationActivity.ATTACHMENT_PATH);
        final File file = new File(filePath);
        sendMediaMessage(file.getAbsolutePath());
    }

    private void handleCameraResult() {
        if (this.captureImageFilename == null) {
            LogUtil.exception(getClass(), "Error during sending camera image");
            return;
        }

        final File file = new File(BaseApplication.get().getFilesDir(), this.captureImageFilename);
        final Subscription sub =
                new FileUtil().compressImage(FileUtil.MAX_SIZE, file)
                .subscribe(
                        compressedFile -> sendMediaMessage(compressedFile.getAbsolutePath()),
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private void sendMediaMessage(final String filePath) {
        final Message message = new Message();
        final String messageBody = SofaAdapters.get().toJson(message);
        final SofaMessage sofaMessage = new SofaMessage()
                .makeNew(getCurrentLocalUser(), messageBody)
                .setAttachmentFilePath(filePath);
        sendMediaMessage(sofaMessage);
    }

    private void sendMediaMessage(final SofaMessage sofaMessage) {
        Single.fromCallable(() -> {
            while (!this.isConversationLoaded) {
                Thread.sleep(50);
            }
            return sofaMessage;
        })
        .subscribeOn(Schedulers.io())
        .subscribe(
                this.outgoingMessageQueue::send,
                this::handleError
        );
    }

    private void handleError(final Throwable throwable) {
        LogUtil.exception(getClass(), throwable);
    }

    @Override
    public void onViewDetached() {
        this.lastVisibleMessagePosition = this.layoutManager.findLastCompletelyVisibleItemPosition();
        this.subscriptions.clear();
        this.messageAdapter.clear();
        this.isConversationLoaded = false;
        this.activity = null;
    }

    @Override
    public void onDestroyed() {
        this.messageAdapter = null;
        stopListeningForMessageChanges();
        ChatNotificationManager.stopNotificationSuppression();
        this.subscriptions = null;
        this.chatObservables = null;
        this.outgoingMessageQueue.clear();
        this.outgoingMessageQueue = null;
    }

    private void stopListeningForMessageChanges() {
        BaseApplication
                .get()
                .getSofaMessageManager()
                .stopListeningForChanges();
    }

    public void onSaveInstanceState(final Bundle outState) {
        outState.putString(CAPTURE_FILENAME, this.captureImageFilename);
    }

    public void onRestoreInstanceState(final Bundle savedInstanceState) {
        this.captureImageFilename = savedInstanceState.getString(CAPTURE_FILENAME);
    }

    private User getCurrentLocalUser() {
        // Yes, this blocks. But realistically, a value should be always ready for returning.
        return BaseApplication
                .get()
                .getUserManager()
                .getCurrentUser()
                .toBlocking()
                .value();
    }

    private Single<Recipient> getRecipient() {
        return Single
                .fromCallable(() -> {
                    while (this.recipient == null) {
                        Thread.sleep(100);
                    }
                    return this.recipient;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io());
    }
}