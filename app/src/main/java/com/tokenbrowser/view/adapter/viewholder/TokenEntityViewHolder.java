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

package com.tokenbrowser.view.adapter.viewholder;

import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tokenbrowser.R;
import com.tokenbrowser.model.local.TokenEntity;
import com.tokenbrowser.model.local.User;
import com.tokenbrowser.model.network.App;
import com.tokenbrowser.util.ImageUtil;
import com.tokenbrowser.view.BaseApplication;
import com.tokenbrowser.view.adapter.listeners.OnItemClickListener;
import com.tokenbrowser.view.custom.StarRatingView;

public class TokenEntityViewHolder<T extends TokenEntity> extends RecyclerView.ViewHolder {

    private @NonNull ImageView avatar;
    private @NonNull TextView displayName;
    private @NonNull TextView info;
    private @NonNull LinearLayout ratingWrapper;
    private @NonNull StarRatingView ratingView;
    private @NonNull TextView reviewCount;

    public TokenEntityViewHolder(View itemView) {
        super(itemView);
        this.avatar = (ImageView) itemView.findViewById(R.id.avatar);
        this.displayName = (TextView) itemView.findViewById(R.id.display_name);
        this.info = (TextView) itemView.findViewById(R.id.info);
        this.ratingWrapper = (LinearLayout) itemView.findViewById(R.id.rating_wrapper);
        this.ratingView = (StarRatingView) itemView.findViewById(R.id.rating_view);
        this.reviewCount = (TextView) itemView.findViewById(R.id.review_count);
    }

    public TokenEntityViewHolder setTokenEntity(final T elem) {
        ImageUtil.loadFromNetwork(elem.getAvatar(), this.avatar);
        this.displayName.setText(elem.getDisplayName());
        setInfo(elem);
        setRatingView(elem);
        return this;
    }

    private void setInfo(final T elem) {
        if (elem instanceof App) {
            this.info.setMaxLines(1);
        } else if (elem instanceof User) {
            this.info.setMaxLines(2);
        }
        this.info.setText(elem.getAbout());
    }

    private void setRatingView(final T elem) {
        if (elem instanceof App) {
            this.ratingWrapper.setVisibility(View.VISIBLE);
            this.ratingView.setStars(elem.getReputationScore());
            final String reviewCount = BaseApplication.get().getString(R.string.parentheses, elem.getReviewCount());
            this.reviewCount.setText(reviewCount);
        } else if (elem instanceof User) {
            this.ratingWrapper.setVisibility(View.GONE);
        }
    }

    public TokenEntityViewHolder setOnClickListener(final OnItemClickListener<T> listener,
                                                    final T elem) {
        this.itemView.setOnClickListener(__ -> listener.onItemClick(elem));
        return this;
    }
}