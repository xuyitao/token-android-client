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
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tokenbrowser.R;
import com.tokenbrowser.util.FileUtil;
import com.tokenbrowser.util.ImageUtil;
import com.tokenbrowser.view.BaseApplication;
import com.tokenbrowser.view.adapter.listeners.OnItemClickListener;

public class FileViewHolder extends RecyclerView.ViewHolder {

    private @NonNull LinearLayout wrapper;
    private @NonNull TextView displayName;
    private @NonNull TextView fileSize;
    private @Nullable ImageView avatar;

    private String path;
    private String avatarUri;

    public FileViewHolder(View v) {
        super(v);
        this.wrapper = (LinearLayout) v.findViewById(R.id.wrapper);
        this.displayName = (TextView) v.findViewById(R.id.display_name);
        this.fileSize = (TextView) v.findViewById(R.id.file_size);
        this.avatar = (ImageView) v.findViewById(R.id.avatar);
    }

    public FileViewHolder setAttachmentPath(final String path) {
        this.path = path;
        return this;
    }

    public FileViewHolder setAvatarUri(final String avatarUri) {
        this.avatarUri = avatarUri;
        return this;
    }

    public FileViewHolder draw() {
        setPath(this.path);
        renderAvatar();
        return this;
    }

    private void setPath(final String path) {
        final String fileName = new FileUtil().getFilenameFromPath(path);
        this.displayName.setText(fileName);

        final long bytes = new FileUtil().getFileSize(path);
        final String fileSizeText = Formatter.formatFileSize(BaseApplication.get(), bytes);
        this.fileSize.setText(fileSizeText);
    }

    private void renderAvatar() {
        if (this.avatar == null) return;
        ImageUtil.load(this.avatarUri, this.avatar);
    }

    public FileViewHolder setOnClickListener(final OnItemClickListener<String> listener,
                                             final String attachmentPath) {
        this.wrapper.setOnClickListener(__ -> listener.onItemClick(attachmentPath));
        return this;
    }
}
