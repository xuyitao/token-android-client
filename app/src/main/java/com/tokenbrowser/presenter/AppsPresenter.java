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
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Patterns;
import android.view.View;

import com.jakewharton.rxbinding.widget.RxTextView;
import com.tokenbrowser.manager.AppsManager;
import com.tokenbrowser.manager.UserManager;
import com.tokenbrowser.model.local.Dapp;
import com.tokenbrowser.model.local.TokenEntity;
import com.tokenbrowser.model.local.User;
import com.tokenbrowser.model.network.App;
import com.tokenbrowser.util.BrowseType;
import com.tokenbrowser.util.LogUtil;
import com.tokenbrowser.view.BaseApplication;
import com.tokenbrowser.view.activity.BrowseActivity;
import com.tokenbrowser.view.activity.ViewUserActivity;
import com.tokenbrowser.view.activity.WebViewActivity;
import com.tokenbrowser.view.adapter.HorizontalAdapter;
import com.tokenbrowser.view.adapter.SearchAppAdapter;
import com.tokenbrowser.view.fragment.toplevel.AppsFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

import static android.view.inputmethod.EditorInfo.IME_ACTION_DONE;
import static com.tokenbrowser.util.BrowseType.VIEW_TYPE_LATEST_APPS;
import static com.tokenbrowser.util.BrowseType.VIEW_TYPE_LATEST_PUBLIC_USERS;
import static com.tokenbrowser.util.BrowseType.VIEW_TYPE_TOP_RATED_APPS;
import static com.tokenbrowser.util.BrowseType.VIEW_TYPE_TOP_RATED_PUBLIC_USERS;

public class AppsPresenter implements Presenter<AppsFragment>{

    private AppsFragment fragment;
    private CompositeSubscription subscriptions;
    private boolean firstTimeAttaching = true;

    @Override
    public void onViewAttached(AppsFragment view) {
        this.fragment = view;

        if (this.firstTimeAttaching) {
            this.firstTimeAttaching = false;
            initLongLivingObjects();
        }

        initView();
        fetchData();
    }

    private void initLongLivingObjects() {
        this.subscriptions = new CompositeSubscription();
        tryRerunQuery();
    }

    private void tryRerunQuery() {
        final String searchQuery = this.fragment.getBinding().search.getText().toString();
        if (searchQuery.length() > 0) {
            runSearchQuery(searchQuery);
        }
    }

    private void initView() {
        initClickListeners();
        initSearchAppsRecyclerView();
        iniTopRatedAppsRecycleView();
        initLatestAppsRecycleView();
        initTopRatedPublicUsersRecyclerView();
        initLatestPublicUsersRecyclerView();
        initSearchView();
    }

    private void initClickListeners() {
        this.fragment.getBinding().moreTopRatedApps.setOnClickListener(__ -> startBrowseActivity(VIEW_TYPE_TOP_RATED_APPS));
        this.fragment.getBinding().moreLatestApps.setOnClickListener(__ -> startBrowseActivity(VIEW_TYPE_LATEST_APPS));
        this.fragment.getBinding().moreTopRatedPublicUsers.setOnClickListener(__ -> startBrowseActivity(VIEW_TYPE_TOP_RATED_PUBLIC_USERS));
        this.fragment.getBinding().moreLatestPublicUsers.setOnClickListener(__ -> startBrowseActivity(VIEW_TYPE_LATEST_PUBLIC_USERS));
    }

    private void startBrowseActivity(final @BrowseType.Type int viewType) {
        final Intent intent = new Intent(this.fragment.getActivity(), BrowseActivity.class)
                .putExtra(BrowseActivity.VIEW_TYPE, viewType);
        this.fragment.startActivity(intent);
    }

    private void initSearchAppsRecyclerView() {
        final RecyclerView searchAppList = this.fragment.getBinding().searchList;
        searchAppList.setLayoutManager(new LinearLayoutManager(this.fragment.getContext()));
        final SearchAppAdapter adapter = new SearchAppAdapter(new ArrayList<>())
                .setOnItemClickListener(this::handleAppClicked)
                .setOnDappLaunchListener(this::handleDappLaunch);
        searchAppList.setAdapter(adapter);
    }

    private void iniTopRatedAppsRecycleView() {
        final RecyclerView topRatedApps = this.fragment.getBinding().topRatedApps;
        topRatedApps.setLayoutManager(new LinearLayoutManager(this.fragment.getContext(), LinearLayoutManager.HORIZONTAL, false));
        final HorizontalAdapter adapter = new HorizontalAdapter<App>()
                .setOnItemClickListener(this::handleAppClicked);
        topRatedApps.setAdapter(adapter);
        topRatedApps.setNestedScrollingEnabled(false);
    }

    private void initLatestAppsRecycleView() {
        final RecyclerView topRatedApps = this.fragment.getBinding().latestApps;
        topRatedApps.setLayoutManager(new LinearLayoutManager(this.fragment.getContext(), LinearLayoutManager.HORIZONTAL, false));
        final HorizontalAdapter adapter = new HorizontalAdapter<App>()
                .setOnItemClickListener(this::handleAppClicked);
        topRatedApps.setAdapter(adapter);
        topRatedApps.setNestedScrollingEnabled(false);
    }

    private void initTopRatedPublicUsersRecyclerView() {
        final RecyclerView topRatedApps = this.fragment.getBinding().topRatedPublicUsers;
        topRatedApps.setLayoutManager(new LinearLayoutManager(this.fragment.getContext(), LinearLayoutManager.HORIZONTAL, false));
        final HorizontalAdapter adapter = new HorizontalAdapter<User>()
                .setOnItemClickListener(this::handleUserClicked);
        topRatedApps.setAdapter(adapter);
        topRatedApps.setNestedScrollingEnabled(false);
    }

    private void initLatestPublicUsersRecyclerView() {
        final RecyclerView topRatedApps = this.fragment.getBinding().latestPublicUsers;
        topRatedApps.setLayoutManager(new LinearLayoutManager(this.fragment.getContext(), LinearLayoutManager.HORIZONTAL, false));
        final HorizontalAdapter adapter = new HorizontalAdapter<User>()
                .setOnItemClickListener(this::handleUserClicked);
        topRatedApps.setAdapter(adapter);
        topRatedApps.setNestedScrollingEnabled(false);
    }

    private void handleAppClicked(final Object elem) {
        if (this.fragment == null) return;
        startProfileActivity(((TokenEntity)elem).getTokenId());
    }

    private void handleUserClicked(final Object elem) {
        if (this.fragment == null) return;
        startProfileActivity(((TokenEntity)elem).getTokenId());
    }

    private void startProfileActivity(final String userAddress) {
        final Intent intent = new Intent(this.fragment.getContext(), ViewUserActivity.class)
                .putExtra(ViewUserActivity.EXTRA__USER_ADDRESS, userAddress);
        this.fragment.getContext().startActivity(intent);
    }

    private void handleDappLaunch(final Dapp dapp) {
        final Intent intent = new Intent(this.fragment.getContext(), WebViewActivity.class)
                .putExtra(WebViewActivity.EXTRA__ADDRESS, dapp.getAddress());
        this.fragment.getContext().startActivity(intent);
    }

    private void initSearchView() {
        final Subscription searchSub =
                RxTextView.textChanges(this.fragment.getBinding().search)
                .skip(1)
                .debounce(400, TimeUnit.MILLISECONDS)
                .map(CharSequence::toString)
                .subscribe(
                        this::runSearchQuery,
                        throwable -> LogUtil.exception(getClass(), throwable)
                );

        final Subscription enterSub =
                RxTextView.editorActions(this.fragment.getBinding().search)
                .filter(event -> event == IME_ACTION_DONE)
                .subscribe(
                        __ -> this.handleSearchPressed(),
                        t -> LogUtil.e(getClass(), t.toString())
                );

        updateViewState();

        this.subscriptions.addAll(searchSub, enterSub);
    }

    private void runSearchQuery(final String query) {
        final Subscription sub =
            Observable.just(query)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(__ -> updateViewState())
                .doOnNext(this::tryRenderDappLink)
                .observeOn(Schedulers.io())
                .flatMap(this::searchApps)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleAppSearchResponse,
                        this::handleAppSearchError
                );
        this.subscriptions.add(sub);
    }

    private void handleSearchPressed() {
        if (getSearchAdapter() == null || getSearchAdapter().getNumberOfApps() != 1) return;
        final App appToLaunch = getSearchAdapter().getFirstApp();
        if (appToLaunch == null) return;
        if (appToLaunch instanceof Dapp) {
            this.handleDappLaunch((Dapp) appToLaunch);
        }
    }

    private Observable<List<App>> searchApps(final String searchString) {
        return BaseApplication
                .get()
                .getAppsManager()
                .searchApps(searchString);
    }

    private void handleAppSearchError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error while searching for app", throwable);
    }

    private void updateViewState() {
        final boolean shouldShowSearchResult = this.fragment.getBinding().search.getText().toString().length() > 0;

        if (shouldShowSearchResult) {
            this.fragment.getBinding().searchList.setVisibility(View.VISIBLE);
            this.fragment.getBinding().scrollView.setVisibility(View.GONE);
        } else {
            this.fragment.getBinding().searchList.setVisibility(View.GONE);
            this.fragment.getBinding().scrollView.setVisibility(View.VISIBLE);
        }
    }

    private void tryRenderDappLink(final String searchString) {
        if (!Patterns.WEB_URL.matcher(searchString.trim()).matches()) {
            getSearchAdapter().removeDapp();
            return;
        }

        getSearchAdapter().addDapp(searchString);
    }

    private void handleAppSearchResponse(final List<App> apps) {
        getSearchAdapter().addItems(apps);
    }

    private SearchAppAdapter getSearchAdapter() {
        return (SearchAppAdapter) this.fragment.getBinding().searchList.getAdapter();
    }

    private void fetchData() {
        fetchTopRatedApps();
        fetchLatestApps();
        fetchTopRatedPublicUsers();
        fetchLatestPublicUsers();
    }

    private void fetchTopRatedApps() {
        final Subscription sub =
                getAppManager()
                .getTopRatedApps(10)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleTopRatedApps,
                        throwable -> LogUtil.exception(getClass(), "Error while fetching top rated apps", throwable)
                );

        this.subscriptions.add(sub);
    }

    private void handleTopRatedApps(final List<App> apps) {
        final HorizontalAdapter<App> adapter = (HorizontalAdapter) this.fragment.getBinding().topRatedApps.getAdapter();
        adapter.setItems(apps);
    }

    private void fetchLatestApps() {
        final Subscription sub =
                getAppManager()
                .getLatestApps(10)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleLatestApps,
                        throwable -> LogUtil.exception(getClass(), "Error while fetching top rated apps", throwable)
                );

        this.subscriptions.add(sub);
    }

    private AppsManager getAppManager() {
        return BaseApplication
                .get()
                .getTokenManager()
                .getAppsManager();
    }

    private void handleLatestApps(final List<App> apps) {
        final HorizontalAdapter<App> adapter = (HorizontalAdapter) this.fragment.getBinding().latestApps.getAdapter();
        adapter.setItems(apps);
    }

    private void fetchTopRatedPublicUsers() {
        final Subscription sub =
                getUserManager()
                .getTopRatedPublicUsers(10)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleTopRatedPublicUser,
                        throwable -> LogUtil.exception(getClass(), "Error while fetching public users", throwable)
                );

        this.subscriptions.add(sub);
    }

    private void handleTopRatedPublicUser(final List<User> users) {
        final HorizontalAdapter<User> adapter = (HorizontalAdapter) this.fragment.getBinding().topRatedPublicUsers.getAdapter();
        adapter.setItems(users);
    }

    private void fetchLatestPublicUsers() {
        final Subscription sub =
                getUserManager()
                .getLatestPublicUsers(10)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleLatestPublicUser,
                        throwable -> LogUtil.exception(getClass(), "Error while fetching public users", throwable)
                );

        this.subscriptions.add(sub);
    }

    private void handleLatestPublicUser(final List<User> users) {
        final HorizontalAdapter<User> adapter = (HorizontalAdapter) this.fragment.getBinding().latestPublicUsers.getAdapter();
        adapter.setItems(users);
    }

    private UserManager getUserManager() {
        return BaseApplication
                .get()
                .getTokenManager()
                .getUserManager();
    }

    @Override
    public void onViewDetached() {
        this.fragment = null;
        this.subscriptions.clear();
    }

    @Override
    public void onDestroyed() {
        this.fragment = null;
    }
}
