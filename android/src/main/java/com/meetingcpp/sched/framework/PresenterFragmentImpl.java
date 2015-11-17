/*
 * Copyright 2015 Google Inc. All rights reserved.
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
package com.meetingcpp.sched.framework;

import android.app.Activity;
import android.app.Fragment;
import android.app.LoaderManager;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.meetingcpp.sched.util.ThrottledContentObserver;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;
import static com.meetingcpp.sched.util.LogUtils.LOGE;
import static com.meetingcpp.sched.util.LogUtils.makeLogTag;


/**
 * Implementation of {@link Presenter} to control the {@link Model} and the {@link UpdatableView}.
 * Custom Presenters can extend this. It uses a non-UI {@link android.app.Fragment} to participate
 * in the {@link android.app.Activity} lifecycle.
 * <p/>
 * Uses {@link UpdatableView.UserActionListener} to listens to user events generated by the
 * {@link UpdatableView}.
 */
public class PresenterFragmentImpl extends Fragment
        implements Presenter, UpdatableView.UserActionListener,
        LoaderManager.LoaderCallbacks<Cursor> {

    /**
     * Key to be used in Bundle passed in {@link #onUserAction(UserActionEnum, android.os.Bundle)}
     * if a new {@link QueryEnum}, specifying its id. The value stored must be an Integer.
     */
    public static final String KEY_RUN_QUERY_ID = "RUN_QUERY_ID";

    private static final String TAG = makeLogTag(PresenterFragmentImpl.class);

    /**
     * The UI that this Presenter controls.
     */
    private UpdatableView mUpdatableView;

    /**
     * The Model that this Presenter controls.
     */
    private Model mModel;

    /**
     * The queries to load when the {@link android.app.Activity} loading this
     * {@link android.app.Fragment} is created.
     */
    private QueryEnum[] mInitialQueriesToLoad;

    /**
     * The actions allowed by the presenter.
     */
    private UserActionEnum[] mValidUserActions;

    /**
     * The Idling Resources that manages the busy/idle state of the cursor loaders.
     */
    private LoaderIdlingResource mLoaderIdlingResource;

    /**
     * The content observers on data changes. This is required if the Fragment is expected to react
     * to data changes that occur while the Fragment is not visible.
     */
    private HashMap<Uri, ThrottledContentObserver> mContentObservers;


    /**
     * Returns the {@link LoaderIdlingResource} that allows the Espresso UI test framework to track
     * the idle/busy state of the cursor loaders used in the {@link Model}.
     */
    public LoaderIdlingResource getLoaderIdlingResource() {
        return mLoaderIdlingResource;
    }

    @Override
    public void setModel(Model model) {
        mModel = model;
    }

    @Override
    public void setUpdatableView(UpdatableView view) {
        mUpdatableView = view;
        mUpdatableView.addListener(this);
    }

    @Override
    public void setInitialQueriesToLoad(QueryEnum[] queries) {
        mInitialQueriesToLoad = queries;
    }

    @Override
    public void setValidUserActions(UserActionEnum[] actions) {
        mValidUserActions = actions;
    }


    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        // Register content observers with the content resolver.
        if (mContentObservers != null) {
            Iterator<Map.Entry<Uri, ThrottledContentObserver>> observers =
                    mContentObservers.entrySet().iterator();
            while (observers.hasNext()) {
                Map.Entry<Uri, ThrottledContentObserver> entry = observers.next();
                activity.getContentResolver().registerContentObserver(
                        entry.getKey(), true, entry.getValue());
            }
        }
    }


    @Override
    public void onDetach() {
        super.onDetach();
        cleanUp();
    }

    @Override
    public void cleanUp() {
        mUpdatableView = null;
        mModel = null;
        if (mContentObservers != null) {
            Iterator<ThrottledContentObserver> observers = mContentObservers.values().iterator();
            while (observers.hasNext()) {
                getActivity().getContentResolver().unregisterContentObserver(observers.next());
            }
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mLoaderIdlingResource =
                new LoaderIdlingResource(getClass().getName() + "/" + getId(), getLoaderManager());

        // Load data queries if any.
        if (mInitialQueriesToLoad != null && mInitialQueriesToLoad.length > 0) {
            LoaderManager manager = getLoaderManager();
            for (int i = 0; i < mInitialQueriesToLoad.length; i++) {
                manager.initLoader(mInitialQueriesToLoad[i].getId(), null, this);
            }
        } else {
            // No data query to load, update the view.
            mUpdatableView.displayData(mModel, null);
        }
    }

    @Override
    public Context getContext() {
        return mUpdatableView.getContext();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        Loader<Cursor> cursorLoader = createLoader(id, args);
        mLoaderIdlingResource.onLoaderStarted(cursorLoader);
        return cursorLoader;
    }

    @VisibleForTesting
    protected Loader<Cursor> createLoader(int id, Bundle args) {
        Uri uri = mUpdatableView.getDataUri(QueryEnumHelper.getQueryForId(id, mModel.getQueries()));
        return mModel.createCursorLoader(id, uri, args);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        processData(loader, data);
        mLoaderIdlingResource.onLoaderFinished(loader);
    }

    @VisibleForTesting
    protected void processData(Loader<Cursor> loader, Cursor data) {
        QueryEnum query = QueryEnumHelper.getQueryForId(loader.getId(), mModel.getQueries());
        boolean successfulDataRead = mModel.readDataFromCursor(data, query);
        if (successfulDataRead) {
            mUpdatableView.displayData(mModel, query);
        } else {
            mUpdatableView.displayErrorMessage(query);
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        /**
         * We just keep track of the Number of running queries. Normally, the reference to the
         * cursor in the UI would be closed (for example, if using a CursorAdapter) to avoid
         * exceptions when the UI tries to get data from a closed Cursor, but as the
         * {@link UpdatableView} takes its data from the {@link Model} and not from the Cursor
         * directly, there is no Cursor reference to release.
         */
        mLoaderIdlingResource.onLoaderFinished(loader);
    }

    /**
     * Called when the user has performed an {@code action}, with data to be passed
     * as a {@link android.os.Bundle} in {@code args}.
     * <p/>
     * Add the constants used to store values in the bundle to the Model implementation class
     * as final static protected strings.
     * <p/>
     * If the {@code action} should trigger a new data query, specify the query ID by storing the
     * associated Integer in the {@code args} using {@link #KEY_RUN_QUERY_ID}. The {@code args} will
     * be passed on to the cursor loader so you can pass in extra arguments for your query.
     */
    @Override
    public void onUserAction(UserActionEnum action, @Nullable Bundle args) {
        boolean isValid = false;
        if (mValidUserActions != null && mValidUserActions.length > 0 && action != null) {
            for (int i = 0; i < mValidUserActions.length; i++) {
                if (mValidUserActions[i].getId() == action.getId()) {
                    isValid = true;
                }
            }
        }
        if (isValid) {
            if (args != null && args.containsKey(KEY_RUN_QUERY_ID)) {
                Object queryId = args.get(KEY_RUN_QUERY_ID);
                if (queryId instanceof Integer) {
                    LoaderManager manager = getLoaderManager();
                    manager.restartLoader((Integer) queryId, args, this);
                } else {
                    // Query id should be an integer!
                    LOGE(TAG, "onUserAction called with a bundle containing KEY_RUN_QUERY_ID but"
                            + "the value is not an Integer so it's not a valid query id!");
                }
            }
            boolean success = mModel.requestModelUpdate(action, args);
            if (!success) {
                // User action not understood by model, even though the presenter understands it.
                LOGE(TAG, "Model doesn't implement user action " + action.getId() + ". Have you "
                        + "forgotten to implement this UserActionEnum in your model, or have you "
                        + "called setValidUserActions on your presenter with a UserActionEnum that "
                        + "it shouldn't support?");
            }
        } else {
            // User action not understood.
            LOGE(TAG, "Invalid user action " + action.getId() + ". Have you called "
                    + "setValidUserActions on your presenter, with all the UserActionEnum you want "
                    + "to support?");
        }
    }

    /**
     * Registers this PresenterFragmentImpl as an explicit {@link ThrottledContentObserver} on the
     * {@code uri}. This is required only if this PresenterFragmentImpl is expected to react to
     * data changes while it is not visible. When a change is observed, the {@code queries} are run.
     */
    public void registerContentObserverOnUri(Uri uri, final QueryEnum[] queriesToRun) {
        checkState(queriesToRun != null && queriesToRun.length > 0, "Error registering content " +
                "observer on uri " + uri + ", you must specify at least one query to run");

        if (mContentObservers == null) {
            mContentObservers = new HashMap<Uri, ThrottledContentObserver>();
        }
        if (!mContentObservers.containsKey(uri)) {

            // Creates callback for content observer and add it to the hash map.
            ThrottledContentObserver observer =
                    new ThrottledContentObserver(new ThrottledContentObserver.Callbacks() {
                        @Override
                        public void onThrottledContentObserverFired() {
                            onObservedContentChanged(queriesToRun);
                        }
                    });
            mContentObservers.put(uri, observer);

        } else {
            // Uri already has a content observer.
            LOGE(TAG, "This presenter is already registered as a content observer for uri " + uri
                    + ", ignoring this call to registerContentObserverOnUri");
        }
    }

    private void onObservedContentChanged(QueryEnum[] queriesToRun) {
        for (int i = 0; i < queriesToRun.length; i++) {
            getLoaderManager().initLoader(queriesToRun[i].getId(), null, this);
        }
    }

    @VisibleForTesting
    public Model getModel() {
        return mModel;
    }

    @VisibleForTesting
    public QueryEnum[] getInitialQueriesToLoad() {
        return mInitialQueriesToLoad;
    }

    @VisibleForTesting
    public UserActionEnum[] getValidUserActions() {
        return mValidUserActions;
    }
}