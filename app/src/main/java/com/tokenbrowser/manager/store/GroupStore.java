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

package com.tokenbrowser.manager.store;


import com.tokenbrowser.model.local.Group;
import com.tokenbrowser.view.BaseApplication;

import io.realm.Realm;
import rx.Single;

public class GroupStore {

    public Single<Group> loadForId(final String id) {
        return Single.fromCallable(() -> loadWhere("id", id));
    }

    private Group loadWhere(final String fieldName, final String value) {
        final Realm realm = BaseApplication.get().getRealm();
        final Group group =
                realm.where(Group.class)
                .equalTo(fieldName, value)
                .findFirst();

        final Group retVal = group == null ? null : realm.copyFromRealm(group);
        realm.close();
        return retVal;
    }
}
