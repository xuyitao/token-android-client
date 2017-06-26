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

package com.tokenbrowser.presenter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.support.annotation.ColorInt;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.content.res.AppCompatResources;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.tokenbrowser.R;
import com.tokenbrowser.databinding.ActivityUserProfileBinding;
import com.tokenbrowser.model.local.ActivityResultHolder;
import com.tokenbrowser.model.local.User;
import com.tokenbrowser.model.network.ReputationScore;
import com.tokenbrowser.util.ImageUtil;
import com.tokenbrowser.util.LogUtil;
import com.tokenbrowser.util.OnSingleClickListener;
import com.tokenbrowser.util.PaymentType;
import com.tokenbrowser.util.RatingHandler;
import com.tokenbrowser.util.SoundManager;
import com.tokenbrowser.util.UserBlockingHandler;
import com.tokenbrowser.util.UserReportingHandler;
import com.tokenbrowser.view.BaseApplication;
import com.tokenbrowser.view.activity.AmountActivity;
import com.tokenbrowser.view.activity.ChatActivity;
import com.tokenbrowser.view.activity.ViewUserActivity;

import rx.Completable;
import rx.Single;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.subscriptions.CompositeSubscription;

public final class ViewUserPresenter implements
        Presenter<ViewUserActivity>,
        UserBlockingHandler.OnBlockingListener {

    private static final int ETH_PAY_CODE = 2;

    private ViewUserActivity activity;
    private CompositeSubscription subscriptions;
    private boolean firstTimeAttached = true;

    private User user;
    private String userAddress;
    private UserBlockingHandler userBlockingHandler;
    private UserReportingHandler userReportingHandler;
    private RatingHandler ratingHandler;

    @Override
    public void onViewAttached(final ViewUserActivity activity) {
        this.activity = activity;
        if (this.firstTimeAttached) {
            this.firstTimeAttached = false;
            initLongLivingObjects();
        }
        initShortLivingObjects();
    }

    private void initLongLivingObjects() {
        this.subscriptions = new CompositeSubscription();
    }

    private void initShortLivingObjects() {
        initToolbar();
        initClickListeners();
        processIntentData();
        initHandlers();
        loadUser();
        fetchUserReputation();
    }

    private void initToolbar() {
        this.activity.setSupportActionBar(this.activity.getBinding().toolbar);
        final ActionBar actionBar = this.activity.getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayShowTitleEnabled(false);
    }

    private void initClickListeners() {
        this.activity.getBinding().closeButton.setOnClickListener((View v) -> this.activity.onBackPressed());
    }

    private void processIntentData() {
        this.userAddress = this.activity.getIntent().getStringExtra(ViewUserActivity.EXTRA__USER_ADDRESS);
    }

    private void initHandlers() {
        this.ratingHandler = new RatingHandler(this.activity);
        this.userReportingHandler = new UserReportingHandler(this.activity)
                .setUserAddress(this.userAddress);
        this.userBlockingHandler = new UserBlockingHandler(this.activity)
                .setUserAddress(this.userAddress)
                .setOnBlockingListener(this);
    }

    private void loadUser() {
        final Subscription userSub =
                getUserById(this.userAddress)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleUserLoaded,
                        this::handleUserLoadingFailed
                );

        this.subscriptions.add(userSub);
    }

    private Single<User> getUserById(final String userAddress) {
        return BaseApplication
                .get()
                .getRecipientManager()
                .getUserFromTokenId(userAddress);
    }

    private void fetchUserReputation() {
        final Subscription reputationSub =
                getReputationScoreById(this.userAddress)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleReputationResponse,
                        this::handleReputationError
                );

        this.subscriptions.add(reputationSub);
    }

    private Single<ReputationScore> getReputationScoreById(final String userAddress) {
         return BaseApplication
                .get()
                .getReputationManager()
                .getReputationScore(userAddress);
    }

    private void handleReputationResponse(final ReputationScore reputationScore) {
        final int repCount = reputationScore.getCount();
        final String ratingText = this.activity.getResources().getQuantityString(R.plurals.ratings, repCount, repCount);
        this.activity.getBinding().reviewCount.setText(ratingText);
        this.activity.getBinding().ratingView.setStars(reputationScore.getScore());
        this.activity.getBinding().reputationScore.setText(String.valueOf(reputationScore.getScore()));
        this.activity.getBinding().ratingInfo.setRatingInfo(reputationScore);
    }

    private void handleReputationError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error during reputation fetching", throwable);
    }

    private void handleUserLoadingFailed(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error during fetching user", throwable);
        if (this.activity != null) {
            this.activity.finish();
            Toast.makeText(this.activity, R.string.error_unknown_user, Toast.LENGTH_LONG).show();
            if (shouldPlayScanSounds()) SoundManager.getInstance().playSound(SoundManager.SCAN_ERROR);
        }
    }

    private void handleUserLoaded(final User user) {
        this.user = user;
        final ActivityUserProfileBinding binding = this.activity.getBinding();
        binding.title.setText(user.getDisplayName());
        binding.name.setText(user.getDisplayName());
        binding.username.setText(user.getUsername());
        binding.about.setText(user.getAbout());
        binding.location.setText(user.getLocation());
        binding.about.setVisibility(user.getAbout() == null ? View.GONE : View.VISIBLE);
        binding.location.setVisibility(user.getLocation() == null ? View.GONE : View.VISIBLE);
        ImageUtil.load(user.getAvatar(), binding.avatar);
        addClickListeners();
        updateAddContactState();
        if (shouldPlayScanSounds()) SoundManager.getInstance().playSound(SoundManager.SCAN_ERROR);
    }

    private boolean shouldPlayScanSounds() {
        return this.activity != null
                && this.activity.getIntent() != null
                && this.activity.getIntent().getBooleanExtra(ChatActivity.EXTRA__PLAY_SCAN_SOUNDS, false);
    }

    private void addClickListeners() {
        this.activity.getBinding().favorite.setOnClickListener(this.handleOnAddContact);
        this.activity.getBinding().favorite.setEnabled(true);
        this.activity.getBinding().messageContactButton.setOnClickListener(this::handleMessageContactButton);
        this.activity.getBinding().pay.setOnClickListener(v -> handlePayClicked());
    }

    private void updateAddContactState() {
        final Subscription sub =
                isAContact(this.user)
                .subscribe(
                        this::updateAddContactState,
                        this::handleContactError
                );

        this.subscriptions.add(sub);
    }

    private void updateAddContactState(final boolean isAContact) {
        final Button addContactButton = this.activity.getBinding().favorite;
        addContactButton.setSoundEffectsEnabled(isAContact);

        final Drawable checkMark = isAContact
                ? AppCompatResources.getDrawable(this.activity, R.drawable.ic_star_selected)
                : AppCompatResources.getDrawable(this.activity, R.drawable.ic_star_unselected);
        addContactButton.setCompoundDrawablesWithIntrinsicBounds(null, checkMark, null, null);

        final @ColorInt int color = isAContact
                ? ContextCompat.getColor(this.activity, R.color.colorPrimary)
                : ContextCompat.getColor(this.activity, R.color.profile_icon_text_color);
        addContactButton.setTextColor(color);
    }

    private final OnSingleClickListener handleOnAddContact = new OnSingleClickListener() {
        @Override
        public void onSingleClick(final View v) {
            final Subscription sub =
                    isAContact(user)
                    .subscribe(
                            this::handleAddContact,
                            throwable -> handleContactError(throwable)
                    );

            subscriptions.add(sub);
        }

        private void handleAddContact(final boolean isAContact) {
            if (isAContact) {
                deleteContact(user)
                        .subscribe(() -> updateAddContactState());
            } else {
                saveContact(user)
                        .doOnCompleted(() -> SoundManager.getInstance().playSound(SoundManager.ADD_CONTACT))
                        .subscribe(() -> updateAddContactState());
            }
        }
    };

    private Single<Boolean> isAContact(final User user) {
        return BaseApplication
                .get()
                .getRecipientManager()
                .isUserAContact(user)
                .observeOn(AndroidSchedulers.mainThread());
    }

    private void handleContactError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error while checking if user is a contact", throwable);
    }

    private Completable deleteContact(final User user) {
        return BaseApplication
            .get()
            .getRecipientManager()
            .deleteContact(user);
    }

    private Completable saveContact(final User user) {
        return BaseApplication
            .get()
            .getRecipientManager()
            .saveContact(user);
    }

    private void handleMessageContactButton(final View view) {
        final Intent intent = new Intent(this.activity, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA__THREAD_ID, this.user.getTokenId());
        this.activity.startActivity(intent);
        this.activity.finish();
    }

    private void handlePayClicked() {
        final Intent intent = new Intent(this.activity, AmountActivity.class)
                .putExtra(AmountActivity.VIEW_TYPE, PaymentType.TYPE_SEND);
        this.activity.startActivityForResult(intent, ETH_PAY_CODE);
    }

    public boolean handleActivityResult(final ActivityResultHolder resultHolder) {
        if (resultHolder.getResultCode() != Activity.RESULT_OK || this.activity == null) return false;

        final int requestCode = resultHolder.getRequestCode();
        if (requestCode == ETH_PAY_CODE) {
            goToChatActivityFromPay(resultHolder.getIntent());
        }

        return true;
    }

    private void goToChatActivityFromPay(final Intent payResultIntent) {
        final String ethAmount = payResultIntent.getStringExtra(AmountPresenter.INTENT_EXTRA__ETH_AMOUNT);
        final String userAddress = this.activity.getIntent().getStringExtra(ViewUserActivity.EXTRA__USER_ADDRESS);
        final Intent intent = new Intent(this.activity, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA__THREAD_ID, userAddress)
                .putExtra(ChatActivity.EXTRA__ETH_AMOUNT, ethAmount)
                .putExtra(ChatActivity.EXTRA__PAYMENT_ACTION, PaymentType.TYPE_SEND);
        this.activity.startActivity(intent);
        this.activity.finish();
    }

    public void handleActionMenuClicked(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.rate: {
                this.ratingHandler.rateUser(this.user);
                break;
            }
            case R.id.block: {
                this.userBlockingHandler.blockOrUnblockUser();
                break;
            }
            case R.id.report: {
                this.userReportingHandler.showReportDialog();
                break;
            }
            default: {
                LogUtil.d(getClass(), "Not valid menu item");
            }
        }
    }

    @Override
    public void onUserBlocked() {
        setUnblockedMenuItem();
    }

    @Override
    public void onUserUnblocked() {
        setBlockedMenuItem();
    }

    public boolean shouldCreateOptionsMenu() {
        final boolean isLocalUser = getLocalUser().getTokenId().equals(this.userAddress);
        if (isLocalUser) {
            return false;
        }

        isUserBlocked();
        return true;
    }

    private User getLocalUser() {
        return BaseApplication
                .get()
                .getTokenManager()
                .getUserManager()
                .getCurrentUser()
                .toBlocking()
                .value();
    }

    private void isUserBlocked() {
        final Subscription sub =
                BaseApplication
                .get()
                .getRecipientManager()
                .isUserBlocked(this.userAddress)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleBlockedUser,
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private void handleError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error during fetching user blocked state", throwable);
    }

    private void handleBlockedUser(final boolean isBlocked) {
        if (isBlocked) setUnblockedMenuItem();
        else setBlockedMenuItem();
    }

    private void setBlockedMenuItem() {
        if (this.activity.getMenu() == null) return;
        final MenuItem menuItem = this.activity.getMenu().findItem(R.id.block);
        menuItem.setTitle(this.activity.getString(R.string.block));
    }

    private void setUnblockedMenuItem() {
        if (this.activity.getMenu() == null) return;
        final MenuItem menuItem = this.activity.getMenu().findItem(R.id.block);
        menuItem.setTitle(this.activity.getString(R.string.unblock));
    }

    @Override
    public void onViewDetached() {
        this.userBlockingHandler.clear();
        this.subscriptions.clear();
        this.ratingHandler.clear();
        this.activity = null;
    }

    @Override
    public void onDestroyed() {
        this.userBlockingHandler = null;
        this.subscriptions = null;
        this.ratingHandler = null;
        this.activity = null;
    }
}
