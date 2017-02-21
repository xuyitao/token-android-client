package com.bakkenbaeck.token.view.activity;

import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.support.annotation.NonNull;

import com.bakkenbaeck.token.R;
import com.bakkenbaeck.token.databinding.ActivityBackupPhraseInfoBinding;
import com.bakkenbaeck.token.presenter.BackupPhraseInfoPresenter;
import com.bakkenbaeck.token.presenter.factory.BackupPhraseInfoPresenterFactory;
import com.bakkenbaeck.token.presenter.factory.PresenterFactory;

public class BackupPhraseInfoActivity extends BasePresenterActivity<BackupPhraseInfoPresenter, BackupPhraseInfoActivity> {

    private ActivityBackupPhraseInfoBinding binding;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init();
    }

    private void init() {
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_backup_phrase_info);
    }

    public ActivityBackupPhraseInfoBinding getBinding() {
        return this.binding;
    }

    @NonNull
    @Override
    protected PresenterFactory<BackupPhraseInfoPresenter> getPresenterFactory() {
        return new BackupPhraseInfoPresenterFactory();
    }

    @Override
    protected void onPresenterPrepared(@NonNull final BackupPhraseInfoPresenter presenter) {}

    @Override
    protected int loaderId() {
        return 5005;
    }
}