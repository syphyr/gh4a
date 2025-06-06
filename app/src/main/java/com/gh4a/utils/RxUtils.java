package com.gh4a.utils;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.StringRes;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.snackbar.Snackbar;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.gh4a.ApiRequestException;
import com.gh4a.BaseActivity;
import com.gh4a.R;
import com.meisolsson.githubsdk.model.Page;
import com.meisolsson.githubsdk.model.SearchPage;

import org.reactivestreams.Publisher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import io.reactivex.Single;
import io.reactivex.SingleSource;
import io.reactivex.SingleTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Predicate;
import io.reactivex.processors.PublishProcessor;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Response;

public class RxUtils {
    public static <T> SingleTransformer<List<T>, List<T>> filter(Predicate<T> predicate) {
        return upstream -> upstream.map(list -> {
            List<T> result = new ArrayList<>();
            for (T item : list) {
                if (predicate.test(item)) {
                    result.add(item);
                }
            }
            return result;
        });
    }

    public static <T> SingleTransformer<List<T>, Optional<T>> filterAndMapToFirst(Predicate<T> predicate) {
        return upstream -> upstream.map(list -> {
            for (T item : list) {
                if (predicate.test(item)) {
                    return Optional.of(item);
                }
            }
            return Optional.empty();
        });
    }

    public static <T, R> SingleTransformer<List<T>, List<R>> mapList(Function<T, R> transformer) {
        return upstream -> upstream.map(list -> {
            List<R> result = new ArrayList<>();
            for (T item : list) {
                result.add(transformer.apply(item));
            }
            return result;
        });
    }

    public static <T> SingleTransformer<List<T>, List<T>> sortList(Comparator<? super T> comparator) {
        return upstream ->  upstream.map(list -> {
            list = new ArrayList<>(list);
            Collections.sort(list, comparator);
            return list;
        });
    }

    public static <T> SingleTransformer<T, T> mapFailureToValue(int code, T value) {
        return upstream -> upstream.onErrorResumeNext(error -> {
            if (error instanceof ApiRequestException) {
                if (((ApiRequestException) error).getStatus() == code) {
                    return Single.just(value);
                }
            }
            return Single.error(error);
        });
    }

    public static <T> Single<T> doInBackground(Single<T> upstream) {
        return upstream.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public static <T> SingleTransformer<T, T> wrapForBackgroundTask(final BaseActivity activity,
            final @StringRes int dialogMessageResId, final @StringRes int errorMessageResId) {
        return wrapForBackgroundTask(activity, activity.getRootLayout(), dialogMessageResId,
                activity.getString(errorMessageResId));
    }

    public static <T> SingleTransformer<T, T> wrapForBackgroundTask(final BaseActivity activity,
            final @StringRes int dialogMessageResId, final String errorMessage) {
        return wrapForBackgroundTask(activity, activity.getRootLayout(),
                dialogMessageResId, errorMessage);
    }

    public static <T> SingleTransformer<T, T> wrapForBackgroundTask(final FragmentActivity activity,
            final CoordinatorLayout rootLayout, final @StringRes int dialogMessageResId,
            final @StringRes int errorMessageResId) {
        return wrapForBackgroundTask(activity, rootLayout, dialogMessageResId,
                activity.getString(errorMessageResId));
    }

    public static <T> SingleTransformer<T, T> wrapForBackgroundTask(final FragmentActivity activity,
            final CoordinatorLayout rootLayout, final @StringRes int dialogMessageResId,
            final String errorMessage) {
        return upstream -> upstream
                .compose(RxUtils::doInBackground)
                .compose(wrapWithProgressDialog(activity, dialogMessageResId))
                .compose(wrapWithErrorSnackbar(activity, rootLayout, errorMessage));
    }

    public static <T> SingleTransformer<T, T> wrapWithProgressDialog(final FragmentActivity activity,
            final @StringRes int messageResId) {
        return new SingleTransformer<T, T>() {
            private ProgressDialogFragment mFragment;

            @Override
            public SingleSource<T> apply(Single<T> upstream) {
                return upstream
                        .doOnSubscribe(disposable -> showDialog())
                        .doOnError(throwable -> hideDialog())
                        .doOnSuccess(result -> hideDialog());
            }

            private void showDialog() {
                Bundle args = new Bundle();
                args.putInt("message_res", messageResId);
                mFragment = new ProgressDialogFragment();
                mFragment.setArguments(args);
                mFragment.show(activity.getSupportFragmentManager(), "progressdialog");
            }

            private void hideDialog() {
                if (mFragment.getActivity() != null) {
                    mFragment.dismissAllowingStateLoss();
                }
                mFragment = null;
            }
        };
    }

    public static <T> SingleTransformer<T, T> wrapWithErrorSnackbar(BaseActivity activity, @StringRes int errorMessageResId) {
        return wrapWithErrorSnackbar(activity, activity.getRootLayout(), activity.getString(errorMessageResId));
    }

    public static <T> SingleTransformer<T, T> wrapWithErrorSnackbar(Context context,
            final CoordinatorLayout rootLayout, final String errorMessage) {
        return new SingleTransformer<>() {
            @Override
            public SingleSource<T> apply(Single<T> upstream) {
                return upstream.retryWhen(errorFlow -> errorFlow.flatMap(this::showErrorSnackbar));
            }

            private Publisher<Integer> showErrorSnackbar(Throwable error) {
                final PublishProcessor<Integer> retryProcessor = PublishProcessor.create();

                var snackbar = Snackbar.make(rootLayout, "", Snackbar.LENGTH_LONG)
                        .addCallback(new Snackbar.BaseCallback<>() {
                            @Override
                            public void onDismissed(Snackbar snackbar, int event) {
                                // Propagate error if opportunity to retry isn't used, either
                                // by dismissing the Snackbar or letting it time out
                                if (event == DISMISS_EVENT_SWIPE || event == DISMISS_EVENT_TIMEOUT) {
                                    retryProcessor.onError(error);
                                }
                            }
                        })
                        .setTextMaxLines(3);

                setSnackBarContentForError(snackbar, error, retryProcessor);
                snackbar.show();
                return retryProcessor;
            }

            private void setSnackBarContentForError(Snackbar snackbar, Throwable error, PublishProcessor<Integer> retryProcessor) {
                final int LONG_ERROR_SNACKBAR_DURATION_MS = 8000;
                if (error instanceof IOException) {
                    snackbar.setText(context.getString(R.string.snackbar_network_error, errorMessage))
                            .setAction(R.string.retry, view -> retryProcessor.onNext(0));
                } else if (error instanceof ApiRequestException) {
                    snackbar
                        .setText(context.getString(R.string.snackbar_github_api_error, errorMessage, error.getMessage()))
                        .setDuration(LONG_ERROR_SNACKBAR_DURATION_MS)
                        .setAction(
                            R.string.snackbar_copy_error_msg_action,
                            view -> IntentUtils.copyToClipboard(context, "GitHub error", error.getMessage())
                        );
                } else {
                    snackbar
                        .setText(context.getString(R.string.snackbar_application_error, errorMessage))
                        .setDuration(LONG_ERROR_SNACKBAR_DURATION_MS)
                        .setAction(
                            R.string.snackbar_copy_error_log_action,
                            view -> IntentUtils.copyToClipboard(context, "OctoDroid error", Log.getStackTraceString(error))
                        );
                }
            }
        };
    }

    public static <T> Single<Optional<T>> toSingleOrFallback(Optional<T> opt, Supplier<Single<Optional<T>>> fallbackSupplier) {
        return opt.isPresent() ? Single.just(opt) : fallbackSupplier.get();
    }

    public static <T, R> Single<Optional<R>> mapToSingle(Optional<T> opt, Function<T, Single<R>> mapper) {
        return opt.isPresent() ? mapper.apply(opt.get()).map(Optional::of) : Single.just(Optional.empty());
    }

    public static <T> Single<Response<Page<T>>> searchPageAdapter(Single<Response<SearchPage<T>>> upstream) {
        return searchPageAdapter(upstream, item -> item);
    }

    public static <U, D> Single<Response<Page<D>>> searchPageAdapter(Single<Response<SearchPage<U>>> upstream, Function<U, D> mapper) {
        return upstream.map(response -> {
            if (response.isSuccessful()) {
                return Response.success(new ApiHelpers.SearchPageAdapter(response.body(), mapper));
            }
            return Response.error(response.errorBody(), response.raw());
        });
    }

    public static class ProgressDialogFragment extends DialogFragment {
        @androidx.annotation.NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            int messageResId = getArguments().getInt("message_res", 0);
            return UiUtils.createProgressDialog(getActivity(), messageResId);
        }
    }
}
