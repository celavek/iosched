/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.meetingcpp.sched.session;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.widget.Toolbar;
import android.view.View;

import com.meetingcpp.sched.R;
import com.meetingcpp.sched.session.SessionDetailModel.SessionDetailQueryEnum;
import com.meetingcpp.sched.session.SessionDetailModel.SessionDetailUserActionEnum;
import com.meetingcpp.sched.ui.BaseActivity;
import com.meetingcpp.sched.myschedule.MyScheduleActivity;
import com.meetingcpp.sched.util.BeamUtils;
import com.meetingcpp.sched.util.LogUtils;
import com.meetingcpp.sched.util.SessionsHelper;
import com.meetingcpp.sched.util.UIUtils;

import static com.meetingcpp.sched.util.LogUtils.LOGE;

/**
 * Displays the details about a session. This Activity is launched via an {@code Intent} with
 * {@link Intent#ACTION_VIEW} and a {@link Uri} built with
 * {@link com.meetingcpp.sched.provider.ScheduleContract.Sessions#buildSessionUri(String)}.
 */
public class SessionDetailActivity extends BaseActivity {

    private static final String TAG = LogUtils.makeLogTag(SessionDetailActivity.class);

    private Handler mHandler = new Handler();

    private Uri mSessionUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UIUtils.tryTranslateHttpIntent(this);
        BeamUtils.tryUpdateIntentFromBeam(this);
        boolean shouldBeFloatingWindow = shouldBeFloatingWindow();
        if (shouldBeFloatingWindow) {
            setupFloatingWindow(R.dimen.session_details_floating_width,
                    R.dimen.session_details_floating_height, 1, 0.4f);
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.session_detail_act);

        final Toolbar toolbar = getActionBarToolbar();
        toolbar.setNavigationIcon(shouldBeFloatingWindow
                ? R.drawable.ic_ab_close : R.drawable.ic_up);
        toolbar.setNavigationContentDescription(R.string.close_and_go_back);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                // Do not display the Activity name in the toolbar
                toolbar.setTitle("");
            }
        });

        if (savedInstanceState == null) {
            Uri sessionUri = getIntent().getData();
            BeamUtils.setBeamSessionUri(this, sessionUri);
        }

        mSessionUri = getIntent().getData();

        if (mSessionUri == null) {
            LOGE(TAG, "SessionDetailActivity started with null session Uri!");
            finish();
            return;
        }

        addPresenterFragment(R.id.session_detail_frag,
                new SessionDetailModel(mSessionUri, getApplicationContext(),
                        new SessionsHelper(this)), SessionDetailQueryEnum.values(),
                SessionDetailUserActionEnum.values());
    }

    public Uri getSessionUri() {
        return mSessionUri;
    }

    @Override
    public Intent getParentActivityIntent() {
        return new Intent(this, MyScheduleActivity.class);
    }
}
