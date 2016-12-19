/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.contacts.model;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SyncStatusObserver;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.contacts.Experiments;
import com.android.contacts.R;
import com.android.contacts.list.ContactListFilterController;
import com.android.contacts.model.account.AccountInfo;
import com.android.contacts.model.account.AccountType;
import com.android.contacts.model.account.AccountTypeProvider;
import com.android.contacts.model.account.AccountTypeWithDataSet;
import com.android.contacts.model.account.AccountWithDataSet;
import com.android.contacts.model.account.FallbackAccountType;
import com.android.contacts.model.account.GoogleAccountType;
import com.android.contacts.model.dataitem.DataKind;
import com.android.contacts.util.concurrent.ContactsExecutors;
import com.android.contactsbind.experiments.Flags;
import com.google.common.base.Preconditions;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * Singleton holder for all parsed {@link AccountType} available on the
 * system, typically filled through {@link PackageManager} queries.
 */
public abstract class AccountTypeManager {
    static final String TAG = "AccountTypeManager";

    private static final Object mInitializationLock = new Object();
    private static AccountTypeManager mAccountTypeManager;

    public static final String BROADCAST_ACCOUNTS_CHANGED = AccountTypeManager.class.getName() +
            ".AccountsChanged";

    /**
     * Requests the singleton instance of {@link AccountTypeManager} with data bound from
     * the available authenticators. This method can safely be called from the UI thread.
     */
    public static AccountTypeManager getInstance(Context context) {
        if (!hasRequiredPermissions(context)) {
            // Hopefully any component that depends on the values returned by this class
            // will be restarted if the permissions change.
            return EMPTY;
        }
        synchronized (mInitializationLock) {
            if (mAccountTypeManager == null) {
                context = context.getApplicationContext();
                mAccountTypeManager = new AccountTypeManagerImpl(context);
            }
        }
        return mAccountTypeManager;
    }

    /**
     * Set the instance of account type manager.  This is only for and should only be used by unit
     * tests.  While having this method is not ideal, it's simpler than the alternative of
     * holding this as a service in the ContactsApplication context class.
     *
     * @param mockManager The mock AccountTypeManager.
     */
    public static void setInstanceForTest(AccountTypeManager mockManager) {
        synchronized (mInitializationLock) {
            mAccountTypeManager = mockManager;
        }
    }

    private static final AccountTypeManager EMPTY = new AccountTypeManager() {

        @Override
        public List<AccountWithDataSet> getAccounts(boolean contactWritableOnly) {
            return Collections.emptyList();
        }

        @Override
        public ListenableFuture<List<AccountInfo>> getAccountsAsync() {
            return Futures.immediateFuture(Collections.<AccountInfo>emptyList());
        }

        @Override
        public ListenableFuture<List<AccountInfo>> filterAccountsAsync(
                Predicate<AccountInfo> filter) {
            return Futures.immediateFuture(Collections.<AccountInfo>emptyList());
        }

        @Override
        public AccountInfo getAccountInfoForAccount(AccountWithDataSet account) {
            return null;
        }

        @Override
        public List<AccountWithDataSet> getGroupWritableAccounts() {
            return Collections.emptyList();
        }

        @Override
        public Account getDefaultGoogleAccount() {
            return null;
        }

        @Override
        public AccountType getAccountType(AccountTypeWithDataSet accountTypeWithDataSet) {
            return null;
        }
    };

    /**
     * Returns the list of all accounts (if contactWritableOnly is false) or just the list of
     * contact writable accounts (if contactWritableOnly is true).
     */
    // TODO: Consider splitting this into getContactWritableAccounts() and getAllAccounts()
    public abstract List<AccountWithDataSet> getAccounts(boolean contactWritableOnly);

    /**
     * Loads accounts in background and returns future that will complete with list of all accounts
     */
    public abstract ListenableFuture<List<AccountInfo>> getAccountsAsync();

    /**
     * Loads accounts and applies the fitler returning only for which the predicate is true
     */
    public abstract ListenableFuture<List<AccountInfo>> filterAccountsAsync(
            Predicate<AccountInfo> filter);

    public abstract AccountInfo getAccountInfoForAccount(AccountWithDataSet account);

    /**
     * Returns the list of accounts that are group writable.
     */
    public abstract List<AccountWithDataSet> getGroupWritableAccounts();

    /**
     * Returns the default google account.
     */
    public abstract Account getDefaultGoogleAccount();

    /**
     * Returns the Google Accounts.
     *
     * <p>This method exists in addition to filterAccountsByTypeAsync because it should be safe
     * to call synchronously.
     * </p>
     */
    public List<AccountInfo> getWritableGoogleAccounts() {
        // This implementation may block and should be overridden by the Impl class
        return Futures.getUnchecked(filterAccountsAsync(new Predicate<AccountInfo>() {
            @Override
            public boolean apply(@Nullable AccountInfo input) {
                return  input.getType().areContactsWritable() &&
                        GoogleAccountType.ACCOUNT_TYPE.equals(input.getType().accountType);
            }
        }));
    }

    /**
     * Returns true if there are real accounts (not "local" account) in the list of accounts.
     */
    public boolean hasNonLocalAccount() {
        final List<AccountWithDataSet> allAccounts = getAccounts(/* contactWritableOnly */ false);
        if (allAccounts == null || allAccounts.size() == 0) {
            return false;
        }
        if (allAccounts.size() > 1) {
            return true;
        }
        return !allAccounts.get(0).isNullAccount();
    }

    static Account getDefaultGoogleAccount(AccountManager accountManager,
            SharedPreferences prefs, String defaultAccountKey) {
        // Get all the google accounts on the device
        final Account[] accounts = accountManager.getAccountsByType(
                GoogleAccountType.ACCOUNT_TYPE);
        if (accounts == null || accounts.length == 0) {
            return null;
        }

        // Get the default account from preferences
        final String defaultAccount = prefs.getString(defaultAccountKey, null);
        final AccountWithDataSet accountWithDataSet = defaultAccount == null ? null :
                AccountWithDataSet.unstringify(defaultAccount);

        // Look for an account matching the one from preferences
        if (accountWithDataSet != null) {
            for (int i = 0; i < accounts.length; i++) {
                if (TextUtils.equals(accountWithDataSet.name, accounts[i].name)
                        && TextUtils.equals(accountWithDataSet.type, accounts[i].type)) {
                    return accounts[i];
                }
            }
        }

        // Just return the first one
        return accounts[0];
    }

    public abstract AccountType getAccountType(AccountTypeWithDataSet accountTypeWithDataSet);

    public final AccountType getAccountType(String accountType, String dataSet) {
        return getAccountType(AccountTypeWithDataSet.get(accountType, dataSet));
    }

    public final AccountType getAccountTypeForAccount(AccountWithDataSet account) {
        if (account != null) {
            return getAccountType(account.getAccountTypeWithDataSet());
        }
        return getAccountType(null, null);
    }

    /**
     * Find the best {@link DataKind} matching the requested
     * {@link AccountType#accountType}, {@link AccountType#dataSet}, and {@link DataKind#mimeType}.
     * If no direct match found, we try searching {@link FallbackAccountType}.
     */
    public DataKind getKindOrFallback(AccountType type, String mimeType) {
        return type == null ? null : type.getKindForMimetype(mimeType);
    }

    /**
     * @param contactWritableOnly if true, it only returns ones that support writing contacts.
     * @return true when this instance contains the given account.
     */
    public boolean contains(AccountWithDataSet account, boolean contactWritableOnly) {
        for (AccountWithDataSet account_2 : getAccounts(contactWritableOnly)) {
            if (account.equals(account_2)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasGoogleAccount() {
        return getDefaultGoogleAccount() != null;
    }

    private static boolean hasRequiredPermissions(Context context) {
        final boolean canGetAccounts = ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED;
        final boolean canReadContacts = ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        return canGetAccounts && canReadContacts;
    }

    public static Predicate<AccountInfo> nonNullAccountFilter() {
        return new Predicate<AccountInfo>() {
            @Override
            public boolean apply(AccountInfo info) {
                AccountWithDataSet account = info != null ? info.getAccount() : null;
                return account != null && !account.isNullAccount();
            }
        };

    }

    public static Predicate<AccountInfo> writableFilter() {
        return new Predicate<AccountInfo>() {
            @Override
            public boolean apply(AccountInfo account) {
                return account.getType().areContactsWritable();
            }
        };
    }

    public static Predicate<AccountInfo> groupWritableFilter() {
        return new Predicate<AccountInfo>() {
            @Override
            public boolean apply(@Nullable AccountInfo account) {
                return account.getType().isGroupMembershipEditable();
            }
        };
    }

    public static Predicate<AccountInfo> onlyNonEmptyExtensionFilter(Context context) {
        final Context appContext = context.getApplicationContext();
        return new Predicate<AccountInfo>() {
            @Override
            public boolean apply(@Nullable AccountInfo input) {
                return !input.getType().isExtension() || input.getAccount().hasData(appContext);
            }
        };
    }
}

class AccountTypeManagerImpl extends AccountTypeManager
        implements OnAccountsUpdateListener, SyncStatusObserver {

    private Context mContext;
    private AccountManager mAccountManager;
    private DeviceLocalAccountLocator mLocalAccountLocator;
    private AccountTypeProvider mTypeProvider;
    private ListeningExecutorService mExecutor;
    private Executor mMainThreadExecutor;

    private AccountType mFallbackAccountType;

    private ListenableFuture<List<AccountWithDataSet>> mLocalAccountsFuture;
    private ListenableFuture<AccountTypeProvider> mAccountTypesFuture;

    private FutureCallback<Object> mAccountsUpdateCallback = new FutureCallback<Object>() {
        @Override
        public void onSuccess(@Nullable Object result) {
            onAccountsUpdatedInternal();
        }

        @Override
        public void onFailure(Throwable t) {
        }
    };

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadAccountTypes();
        }
    };

    /**
     * Internal constructor that only performs initial parsing.
     */
    public AccountTypeManagerImpl(Context context) {
        mContext = context;
        mLocalAccountLocator = DeviceLocalAccountLocator.create(context);
        mTypeProvider = new AccountTypeProvider(context);
        mFallbackAccountType = new FallbackAccountType(context);

        mAccountManager = AccountManager.get(mContext);

        mExecutor = ContactsExecutors.getDefaultThreadPoolExecutor();
        mMainThreadExecutor = ContactsExecutors.newHandlerExecutor(mMainThreadHandler);

        // Request updates when packages or accounts change
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiver(mBroadcastReceiver, sdFilter);

        // Request updates when locale is changed so that the order of each field will
        // be able to be changed on the locale change.
        filter = new IntentFilter(Intent.ACTION_LOCALE_CHANGED);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mAccountManager.addOnAccountsUpdatedListener(this, mMainThreadHandler, false);

        ContentResolver.addStatusChangeListener(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, this);

        if (Flags.getInstance().getBoolean(Experiments.OEM_CP2_DEVICE_ACCOUNT_DETECTION_ENABLED)) {
            // Observe changes to RAW_CONTACTS so that we will update the list of "Device" accounts
            // if a new device contact is added.
            mContext.getContentResolver().registerContentObserver(
                    ContactsContract.RawContacts.CONTENT_URI, /* notifyDescendents */ true,
                    new ContentObserver(mMainThreadHandler) {
                        @Override
                        public boolean deliverSelfNotifications() {
                            return true;
                        }

                        @Override
                        public void onChange(boolean selfChange) {
                            reloadLocalAccounts();
                        }

                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            reloadLocalAccounts();
                        }
                    });
        }
        loadAccountTypes();
    }

    @Override
    public void onStatusChanged(int which) {
        reloadAccountTypes();
    }

    /* This notification will arrive on the background thread */
    public void onAccountsUpdated(Account[] accounts) {
        onAccountsUpdatedInternal();
    }

    private void onAccountsUpdatedInternal() {
        ContactListFilterController.getInstance(mContext).checkFilterValidity(true);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(
                new Intent(BROADCAST_ACCOUNTS_CHANGED));
    }

    private synchronized void startLoadingIfNeeded() {
        if (mTypeProvider == null && mAccountTypesFuture == null) {
            reloadAccountTypes();
        }
        if (mLocalAccountsFuture == null) {
            reloadLocalAccounts();
        }
    }

    private void loadAccountTypes() {
        mTypeProvider = new AccountTypeProvider(mContext);

        mAccountTypesFuture = mExecutor.submit(new Callable<AccountTypeProvider>() {
            @Override
            public AccountTypeProvider call() throws Exception {
                // This will request the AccountType for each Account
                getAccountsFromProvider(mTypeProvider);
                return mTypeProvider;
            }
        });
    }

    private synchronized void reloadAccountTypes() {
        loadAccountTypes();
        Futures.addCallback(mAccountTypesFuture, mAccountsUpdateCallback, mMainThreadExecutor);
    }

    private synchronized void loadLocalAccounts() {
        mLocalAccountsFuture = mExecutor.submit(new Callable<List<AccountWithDataSet>>() {
            @Override
            public List<AccountWithDataSet> call() throws Exception {
                return mLocalAccountLocator.getDeviceLocalAccounts();
            }
        });
    }

    private void reloadLocalAccounts() {
        loadLocalAccounts();
        Futures.addCallback(mLocalAccountsFuture, mAccountsUpdateCallback, mMainThreadExecutor);
    }

    /**
     * Return list of all known or contact writable {@link AccountWithDataSet}'s.
     * {@param contactWritableOnly} whether to restrict to contact writable accounts only
     */
    @Override
    public List<AccountWithDataSet> getAccounts(boolean contactWritableOnly) {
        final Predicate<AccountInfo> filter = contactWritableOnly ?
                writableFilter() : Predicates.<AccountInfo>alwaysTrue();
        // TODO: Shouldn't have a synchronous version for getting all accounts
        return Lists.transform(Futures.getUnchecked(filterAccountsAsync(filter)),
                AccountInfo.ACCOUNT_EXTRACTOR);
    }

    @Override
    public ListenableFuture<List<AccountInfo>> getAccountsAsync() {
        return getAllAccountsAsyncInternal();
    }

    private ListenableFuture<List<AccountInfo>> getAllAccountsAsyncInternal() {
        startLoadingIfNeeded();
        final AccountTypeProvider typeProvider = mTypeProvider;
        final ListenableFuture<List<AccountInfo>> accountsFromTypes =
                Futures.transform(Futures.nonCancellationPropagating(mAccountTypesFuture),
                        new Function<AccountTypeProvider, List<AccountInfo>>() {
                            @Override
                            public List<AccountInfo> apply(AccountTypeProvider provider) {
                                return getAccountsFromProvider(provider);
                            }
                        });

        final ListenableFuture<List<AccountInfo>> localAccountsInfo =
                Futures.transform(mLocalAccountsFuture, new Function<List<AccountWithDataSet>,
                        List<AccountInfo>>() {
                    @Nullable
                    @Override
                    public List<AccountInfo> apply(@Nullable List<AccountWithDataSet> input) {
                        final List<AccountInfo> result = new ArrayList<>();
                        for (AccountWithDataSet account : input) {
                            final AccountType type = typeProvider.getTypeForAccount(account);
                            result.add(type.wrapAccount(mContext, account));
                        }
                        return result;
                    }
                });

        final ListenableFuture<List<List<AccountInfo>>> all =
                Futures.successfulAsList(accountsFromTypes, localAccountsInfo);

        return Futures.transform(all, new Function<List<List<AccountInfo>>,
                List<AccountInfo>>() {
            @Override
            public List<AccountInfo> apply(List<List<AccountInfo>> input) {
                // input.get(0) contains accounts from AccountManager
                // input.get(1) contains device local accounts
                Preconditions.checkArgument(input.size() == 2,
                        "List should have exactly 2 elements");

                final List<AccountInfo> result = new ArrayList<>(input.get(0));
                // Check if there is a Google account in this list and if there is exclude the null
                // account
                if (hasWritableGoogleAccount(input.get(0))) {
                    result.addAll(Collections2.filter(input.get(1), nonNullAccountFilter()));
                } else {
                    result.addAll(input.get(1));
                }
                AccountInfo.sortAccounts(null, result);
                return result;
            }
        });
    }

    @Override
    public ListenableFuture<List<AccountInfo>> filterAccountsAsync(
            final Predicate<AccountInfo> filter) {
        return Futures.transform(getAllAccountsAsyncInternal(), new Function<List<AccountInfo>,
                List<AccountInfo>>() {
            @Override
            public List<AccountInfo> apply(List<AccountInfo> input) {
                return new ArrayList<>(Collections2.filter(input, filter));
            }
        }, mExecutor);
    }

    @Override
    public AccountInfo getAccountInfoForAccount(AccountWithDataSet account) {
        final AccountType type = mTypeProvider.getTypeForAccount(account);
        if (type == null) {
            return null;
        }
        return type.wrapAccount(mContext, account);
    }

    private List<AccountInfo> getAccountsFromProvider(AccountTypeProvider typeProvider) {
        final List<AccountInfo> result = new ArrayList<>();
        final Account[] accounts = mAccountManager.getAccounts();
        for (Account account : accounts) {
            final List<AccountType> types = typeProvider.getAccountTypes(account.type);
            for (AccountType type : types) {
                result.add(type.wrapAccount(mContext,
                        new AccountWithDataSet(account.name, account.type, type.dataSet)));
            }
        }
        return result;
    }

    private boolean hasWritableGoogleAccount(List<AccountInfo> accounts) {
        if (accounts == null) {
            return false;
        }
        for (AccountInfo info : accounts) {
            AccountWithDataSet account = info.getAccount();
            if (GoogleAccountType.ACCOUNT_TYPE.equals(account.type) && account.dataSet ==  null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return the list of all known, group writable {@link AccountWithDataSet}'s.
     */
    public List<AccountWithDataSet> getGroupWritableAccounts() {
        return Lists.transform(Futures.getUnchecked(
                filterAccountsAsync(groupWritableFilter())), AccountInfo.ACCOUNT_EXTRACTOR);
    }

    /**
     * Returns the default google account specified in preferences, the first google account
     * if it is not specified in preferences or is no longer on the device, and null otherwise.
     */
    @Override
    public Account getDefaultGoogleAccount() {
        final SharedPreferences sharedPreferences =
                mContext.getSharedPreferences(mContext.getPackageName(), Context.MODE_PRIVATE);
        final String defaultAccountKey =
                mContext.getResources().getString(R.string.contact_editor_default_account_key);
        return getDefaultGoogleAccount(mAccountManager, sharedPreferences, defaultAccountKey);
    }

    @Override
    public List<AccountInfo> getWritableGoogleAccounts() {
        final Account[] googleAccounts =
                mAccountManager.getAccountsByType(GoogleAccountType.ACCOUNT_TYPE);
        final List<AccountInfo> result = new ArrayList<>();
        for (Account account : googleAccounts) {
            final AccountWithDataSet accountWithDataSet = new AccountWithDataSet(
                    account.name, account.type, null);
            final AccountType type = mTypeProvider.getTypeForAccount(accountWithDataSet);

            // Accounts with a dataSet (e.g. Google plus accounts) are not writable.
            result.add(type.wrapAccount(mContext, accountWithDataSet));
        }
        return result;
    }

    @Override
    public boolean hasNonLocalAccount() {
        final Account[] accounts = mAccountManager.getAccounts();
        return accounts != null && accounts.length > 0;
    }

    /**
     * Find the best {@link DataKind} matching the requested
     * {@link AccountType#accountType}, {@link AccountType#dataSet}, and {@link DataKind#mimeType}.
     * If no direct match found, we try searching {@link FallbackAccountType}.
     */
    @Override
    public DataKind getKindOrFallback(AccountType type, String mimeType) {
        DataKind kind = null;

        // Try finding account type and kind matching request
        if (type != null) {
            kind = type.getKindForMimetype(mimeType);
        }

        if (kind == null) {
            // Nothing found, so try fallback as last resort
            kind = mFallbackAccountType.getKindForMimetype(mimeType);
        }

        if (kind == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Unknown type=" + type + ", mime=" + mimeType);
            }
        }

        return kind;
    }

    /**
     * Return {@link AccountType} for the given account type and data set.
     */
    @Override
    public AccountType getAccountType(AccountTypeWithDataSet accountTypeWithDataSet) {
        return mTypeProvider.getType(
                accountTypeWithDataSet.accountType, accountTypeWithDataSet.dataSet);
    }
}
