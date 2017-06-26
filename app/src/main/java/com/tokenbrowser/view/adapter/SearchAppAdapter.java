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

package com.tokenbrowser.view.adapter;

import android.support.annotation.IntDef;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.tokenbrowser.R;
import com.tokenbrowser.model.local.AppSearchHeader;
import com.tokenbrowser.model.local.Dapp;
import com.tokenbrowser.model.network.App;
import com.tokenbrowser.view.adapter.listeners.OnItemClickListener;
import com.tokenbrowser.view.adapter.viewholder.SearchAppDappViewHolder;
import com.tokenbrowser.view.adapter.viewholder.SearchAppHeaderViewHolder;
import com.tokenbrowser.view.adapter.viewholder.TokenEntityViewHolder;

import java.util.ArrayList;
import java.util.List;

public class SearchAppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    @IntDef({
            ITEM,
            HEADER,
            DAPP_LINK
    })
    public @interface ViewType {}
    public final static int ITEM = 1;
    public final static int HEADER = 2;
    public final static int DAPP_LINK = 3;

    private final AppSearchHeader appSearchHeader;

    private List<App> apps;
    private Dapp dapp;
    private OnItemClickListener<App> listener;
    private OnItemClickListener<Dapp> dappLaunchClicked;

    public SearchAppAdapter() {
        this.appSearchHeader = new AppSearchHeader();
        this.apps = new ArrayList<>();
        addExtras();
    }

    public SearchAppAdapter setOnItemClickListener(final OnItemClickListener<App> listener) {
        this.listener = listener;
        return this;
    }

    public SearchAppAdapter setOnDappLaunchListener(final OnItemClickListener<Dapp> listener) {
        this.dappLaunchClicked = listener;
        return this;
    }

    public void addItems(final List<App> apps) {
        this.apps.clear();
        this.apps.addAll(apps);
        addExtras();
        this.notifyDataSetChanged();
    }

    private void addExtras() {
        this.apps.add(0, this.appSearchHeader);
        if (this.dapp != null) {
            this.apps.add(dapp);
        }
    }

    public void addDapp(final String DappAddress) {
        this.dapp = new Dapp(DappAddress);
    }

    public void removeDapp() {
        if (this.dapp == null) return;

        this.apps.remove(this.dapp);
        this.dapp = null;
    }

    public App getFirstApp() {
        if (this.apps == null || this.apps.size() <= 1) {
            return null;
        }
        // Header is always at position 0
        return this.apps.get(1);
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        switch (viewType) {
            case HEADER: {
                final View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_search_header, parent, false);
                return new SearchAppHeaderViewHolder(v);
            }
            case DAPP_LINK: {
                final View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item__search_dapp, parent, false);
                return new SearchAppDappViewHolder(v);
            }
            case ITEM:
            default: {
                final View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item__token_entity, parent, false);
                return new TokenEntityViewHolder(v);
            }
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        final @ViewType int viewType = holder.getItemViewType();

        switch (viewType) {
            case DAPP_LINK: {
                final SearchAppDappViewHolder vh = (SearchAppDappViewHolder) holder;
                vh.setDapp(this.dapp);
                vh.setListener(this.dappLaunchClicked);
                break;
            }
            case HEADER: {
                break;
            }
            case ITEM:
            default: {
                final TokenEntityViewHolder vh = (TokenEntityViewHolder) holder;
                final App app = this.apps.get(position);

                vh.setTokenEntity(app)
                        .setOnClickListener(this.listener, app);
                break;
            }
        }
    }

    @Override
    public @ViewType int getItemViewType(int position) {
        return this.apps.get(position).getViewType();
    }

    @Override
    public int getItemCount() {
        return this.apps.size();
    }

    // The list always has one header and that will be the first item in the list
    public boolean isEmpty() {
        return this.apps.size() <= 1;
    }

    public int getNumberOfApps() {
        // Remove the header.
        return this.apps.size() - 1;
    }
}