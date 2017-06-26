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

import android.content.Intent;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;

import com.tokenbrowser.R;
import com.tokenbrowser.manager.AppsManager;
import com.tokenbrowser.manager.UserManager;
import com.tokenbrowser.model.local.TokenEntity;
import com.tokenbrowser.model.local.User;
import com.tokenbrowser.model.network.App;
import com.tokenbrowser.util.BrowseType;
import com.tokenbrowser.util.LogUtil;
import com.tokenbrowser.view.BaseApplication;
import com.tokenbrowser.view.activity.BrowseActivity;
import com.tokenbrowser.view.activity.ViewUserActivity;
import com.tokenbrowser.view.adapter.BrowseAdapter;
import com.tokenbrowser.view.custom.HorizontalLineDivider;

import java.util.List;

import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

import static com.tokenbrowser.util.BrowseType.VIEW_TYPE_LATEST_APPS;
import static com.tokenbrowser.util.BrowseType.VIEW_TYPE_LATEST_PUBLIC_USERS;
import static com.tokenbrowser.util.BrowseType.VIEW_TYPE_TOP_RATED_APPS;
import static com.tokenbrowser.util.BrowseType.VIEW_TYPE_TOP_RATED_PUBLIC_USERS;

public class BrowsePresenter implements Presenter<BrowseActivity> {

    private BrowseActivity activity;
    private CompositeSubscription subscriptions;
    private List<? extends TokenEntity> browseList;

    private boolean firstTimeAttaching = true;
    private int scrollPosition = 0;

    @Override
    public void onViewAttached(BrowseActivity view) {
        this.activity = view;

        if (this.firstTimeAttaching) {
            this.firstTimeAttaching = false;
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
        initRecyclerView();
        fetchData();
    }

    private void initToolbar() {
        this.activity.getBinding().title.setText(getTitle());
    }

    private String getTitle() {
        switch (getViewType()) {
            case VIEW_TYPE_TOP_RATED_APPS: {
                return this.activity.getString(R.string.top_rated_apps);
            }
            case VIEW_TYPE_LATEST_APPS: {
                return this.activity.getString(R.string.featured_apps);
            }
            case VIEW_TYPE_TOP_RATED_PUBLIC_USERS: {
                return this.activity.getString(R.string.top_rated_public_users);
            }
            case VIEW_TYPE_LATEST_PUBLIC_USERS:
            default: {
                return this.activity.getString(R.string.latest_public_users);
            }
        }
    }

    private void initClickListeners() {
        this.activity.getBinding().closeButton.setOnClickListener(__ -> this.activity.finish());
    }

    private void initRecyclerView() {
        final RecyclerView recyclerView = this.activity.getBinding().browseList;
        recyclerView.setLayoutManager(new LinearLayoutManager(this.activity));
        recyclerView.setItemAnimator(new DefaultItemAnimator());

        final int dividerLeftPadding = this.activity.getResources().getDimensionPixelSize(R.dimen.avatar_size_small)
                + this.activity.getResources().getDimensionPixelSize(R.dimen.activity_horizontal_margin)
                + this.activity.getResources().getDimensionPixelSize(R.dimen.list_item_avatar_margin);
        final HorizontalLineDivider lineDivider =
                new HorizontalLineDivider(ContextCompat.getColor(this.activity, R.color.divider))
                        .setLeftPadding(dividerLeftPadding);
        recyclerView.addItemDecoration(lineDivider);

        final boolean isPublicUserViewType = getViewType() == VIEW_TYPE_LATEST_PUBLIC_USERS
                || getViewType() == VIEW_TYPE_TOP_RATED_PUBLIC_USERS;

        final BrowseAdapter adapter = isPublicUserViewType
                ? new BrowseAdapter<User>()
                : new BrowseAdapter<App>();
        adapter.setOnItemClickListener(this::handleItemClicked);
        recyclerView.setAdapter(adapter);
    }

    private void handleItemClicked(final Object elem) {
        final Intent intent = new Intent(this.activity, ViewUserActivity.class)
                .putExtra(ViewUserActivity.EXTRA__USER_ADDRESS, ((TokenEntity) elem).getTokenId());
        this.activity.startActivity(intent);
    }

    private void fetchData() {
        if (getViewType() == VIEW_TYPE_TOP_RATED_APPS) {
            fetchTopRatedApps();
        } else if (getViewType() == VIEW_TYPE_LATEST_APPS) {
            fetchLatestApps();
        } else if (getViewType() == VIEW_TYPE_TOP_RATED_PUBLIC_USERS) {
            fetchTopRatedPublicUsers();
        } else {
            fetchLatestPublicUsers();
        }
    }

    @SuppressWarnings("WrongConstant")
    private @BrowseType.Type int getViewType() {
        return this.activity.getIntent().getIntExtra(BrowseActivity.VIEW_TYPE, VIEW_TYPE_LATEST_PUBLIC_USERS);
    }

    private void fetchTopRatedApps() {
        if (this.browseList != null && this.browseList.size() > 0) {
            handleApps(this.browseList);
            scrollToRetainedPosition();
            return;
        }

        final Subscription sub =
                getAppManager()
                .getTopRatedApps(100)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleApps,
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private void fetchLatestApps() {
        if (this.browseList != null && this.browseList.size() > 0) {
            handleApps(this.browseList);
            scrollToRetainedPosition();
            return;
        }

        final Subscription sub =
                getAppManager()
                .getLatestApps(100)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleApps,
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private AppsManager getAppManager() {
        return BaseApplication
                .get()
                .getTokenManager()
                .getAppsManager();
    }

    private void handleApps(final List<? extends TokenEntity> apps) {
        getAdapter().setItems(apps);
        this.browseList = apps;
    }

    private void fetchTopRatedPublicUsers() {
        if (this.browseList != null && this.browseList.size() > 0) {
            handleUsers(this.browseList);
            scrollToRetainedPosition();
            return;
        }

        final Subscription sub =
                getUserManager()
                .getTopRatedPublicUsers(100)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleUsers,
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private void fetchLatestPublicUsers() {
        if (this.browseList != null && this.browseList.size() > 0) {
            handleUsers(this.browseList);
            scrollToRetainedPosition();
            return;
        }

        final Subscription sub =
                getUserManager()
                .getLatestPublicUsers(100)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleUsers,
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private UserManager getUserManager() {
        return BaseApplication
                .get()
                .getTokenManager()
                .getUserManager();
    }

    private void handleUsers(final List<? extends TokenEntity> users) {
        getAdapter().setItems(users);
        this.browseList = users;
    }

    private BrowseAdapter getAdapter() {
        return (BrowseAdapter) this.activity.getBinding().browseList.getAdapter();
    }

    private void scrollToRetainedPosition() {
        this.activity.getBinding().browseList.getLayoutManager().scrollToPosition(this.scrollPosition);
    }

    private void handleError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error fetching app/public users", throwable);
    }

    @Override
    public void onViewDetached() {
        setScrollState();
        this.subscriptions.clear();
        this.activity = null;
    }

    private void setScrollState() {
        if (this.activity == null) return;
        final LinearLayoutManager layoutManager = (LinearLayoutManager) this.activity.getBinding().browseList.getLayoutManager();
        this.scrollPosition = layoutManager.findFirstCompletelyVisibleItemPosition();
    }

    @Override
    public void onDestroyed() {
        this.subscriptions = null;
        this.activity = null;
    }
}
