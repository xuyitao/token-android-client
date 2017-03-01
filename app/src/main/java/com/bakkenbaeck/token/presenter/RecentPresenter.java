package com.bakkenbaeck.token.presenter;

import android.content.Intent;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;

import com.bakkenbaeck.token.R;
import com.bakkenbaeck.token.model.local.Conversation;
import com.bakkenbaeck.token.view.activity.ChatActivity;
import com.bakkenbaeck.token.view.adapter.RecentAdapter;
import com.bakkenbaeck.token.view.adapter.listeners.OnItemClickListener;
import com.bakkenbaeck.token.view.adapter.listeners.OnUpdateListener;
import com.bakkenbaeck.token.view.custom.HorizontalLineDivider;
import com.bakkenbaeck.token.view.fragment.toplevel.RecentFragment;

public final class RecentPresenter implements
        Presenter<RecentFragment>,
        OnItemClickListener<Conversation>,
        OnUpdateListener {

    private RecentFragment fragment;
    private boolean firstTimeAttaching = true;
    private RecentAdapter adapter;

    @Override
    public void onViewAttached(final RecentFragment fragment) {
        this.fragment = fragment;

        if (this.firstTimeAttaching) {
            this.firstTimeAttaching = false;
            initLongLivingObjects();
        }
        initShortLivingObjects();
    }

    private void initShortLivingObjects() {
        final RecyclerView recyclerView = this.fragment.getBinding().recents;
        final RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this.fragment.getContext());
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setItemAnimator(new DefaultItemAnimator());
        recyclerView.setAdapter(this.adapter);

        final int dividerLeftPadding = this.fragment.getResources().getDimensionPixelSize(R.dimen.avatar_size_small)
                                     + this.fragment.getResources().getDimensionPixelSize(R.dimen.activity_horizontal_margin);
        final HorizontalLineDivider lineDivider =
                new HorizontalLineDivider(ContextCompat.getColor(this.fragment.getContext(), R.color.divider))
                .setLeftPadding(dividerLeftPadding);
        recyclerView.addItemDecoration(lineDivider);

        updateEmptyState();
    }

    private void initLongLivingObjects() {
        this.adapter = new RecentAdapter()
                .setOnItemClickListener(this)
                .setOnUpdateListener(this);
    }

    @Override
    public void onItemClick(final Conversation clickedConversation) {
        final Intent intent = new Intent(this.fragment.getActivity(), ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA__REMOTE_USER_ADDRESS, clickedConversation.getMember().getOwnerAddress());
        this.fragment.startActivity(intent);
    }

    private void updateEmptyState() {
        if (this.fragment == null) {
            return;
        }
        // Hide empty state if we have some content
        final boolean showingEmptyState = this.fragment.getBinding().emptyStateSwitcher.getCurrentView().getId() == this.fragment.getBinding().emptyState.getId();
        final boolean shouldShowEmptyState = this.adapter.getItemCount() == 0;

        if (shouldShowEmptyState && !showingEmptyState) {
            this.fragment.getBinding().emptyStateSwitcher.showPrevious();
        } else if (!shouldShowEmptyState && showingEmptyState) {
            this.fragment.getBinding().emptyStateSwitcher.showNext();
        }
    }

    @Override
    public void onUpdate() {
        updateEmptyState();
    }

    @Override
    public void onViewDetached() {
        this.fragment = null;
    }

    @Override
    public void onViewDestroyed() {
        this.fragment = null;
    }
}
