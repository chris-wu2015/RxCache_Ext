/*
 * Copyright 2015 Victor Albertos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rx_cache2.internal;

import java.util.concurrent.Callable;

import javax.inject.Inject;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.observers.DefaultObserver;
import io.reactivex.schedulers.Schedulers;
import io.rx_cache2.CacheMode;
import io.rx_cache2.ConfigProvider;
import io.rx_cache2.EvictDynamicKey;
import io.rx_cache2.EvictDynamicKeyGroup;
import io.rx_cache2.Reply;
import io.rx_cache2.RxCacheException;
import io.rx_cache2.Source;
import io.rx_cache2.internal.cache.GetDeepCopy;

public final class ProcessorProvidersBehaviour implements ProcessorProviders {
    private final io.rx_cache2.internal.cache.TwoLayersCache twoLayersCache;
    private final Boolean useExpiredDataIfLoaderNotAvailable;
    private final GetDeepCopy getDeepCopy;
    private final Observable<Integer> oProcesses;
    private volatile Boolean hasProcessesEnded;

    @Inject
    public ProcessorProvidersBehaviour(
            io.rx_cache2.internal.cache.TwoLayersCache twoLayersCache,
            Boolean useExpiredDataIfLoaderNotAvailable,
            io.rx_cache2.internal.cache.EvictExpiredRecordsPersistence evictExpiredRecordsPersistence,
            GetDeepCopy getDeepCopy, io.rx_cache2.internal.migration.DoMigrations doMigrations) {
        this.hasProcessesEnded = false;
        this.twoLayersCache = twoLayersCache;
        this.useExpiredDataIfLoaderNotAvailable = useExpiredDataIfLoaderNotAvailable;
        this.getDeepCopy = getDeepCopy;
        this.oProcesses = startProcesses(doMigrations, evictExpiredRecordsPersistence);
    }

    private Observable<Integer> startProcesses(
            io.rx_cache2.internal.migration.DoMigrations doMigrations,
            final io.rx_cache2.internal.cache.EvictExpiredRecordsPersistence evictExpiredRecordsPersistence) {
        Observable<Integer> oProcesses = doMigrations.react().flatMap(new Function<Integer, ObservableSource<Integer>>() {
            @Override
            public ObservableSource<Integer> apply(Integer ignore) throws Exception {
                return evictExpiredRecordsPersistence.startEvictingExpiredRecords();
            }
        }).subscribeOn((Schedulers.io())).observeOn(Schedulers.io(),true).share();


        oProcesses.subscribe(new Consumer<Integer>() {
            @Override
            public void accept(Integer ignore) throws Exception {
                hasProcessesEnded = true;
            }
        });

        return oProcesses;
    }

    @Override
    public <T> Observable<T> process(final io.rx_cache2.ConfigProvider configProvider) {
        return Observable.defer(new Callable<ObservableSource<? extends T>>() {
            @Override
            public ObservableSource<? extends T> call() throws Exception {
                if (hasProcessesEnded) {
                    return getData(configProvider);
                }

                return oProcesses.flatMap(new Function<Integer, ObservableSource<? extends T>>() {
                    @Override
                    public ObservableSource<? extends T> apply(Integer ignore) throws Exception {
                        return getData(configProvider);
                    }
                });
            }
        });
    }

    //VisibleForTesting
    <T> Observable<T> getData(final io.rx_cache2.ConfigProvider configProvider) {
        Observable<Reply> replyObservable;
        if (configProvider.getCacheMode() == CacheMode.DEFAULT) {
            replyObservable = getDataFromLoader(configProvider, null);
            return (Observable<T>) replyObservable.map(new Function<Reply, Object>() {
                @Override
                public Object apply(Reply reply) throws Exception {
                    return ProcessorProvidersBehaviour.this.getReturnType(configProvider, reply);
                }
            });
        }
        final Record<Object> record = twoLayersCache.retrieve(configProvider.getProviderKey(), configProvider.getDynamicKey(),
                configProvider.getDynamicKeyGroup(), useExpiredDataIfLoaderNotAvailable,
                configProvider.getLifeTimeMillis(), configProvider.isEncrypted());
        boolean useExpiredData = useExpiredDataIfLoaderNotAvailable;
        if (configProvider.useExpiredDataIfNotLoaderAvailable() != null) {
            useExpiredData = configProvider.useExpiredDataIfNotLoaderAvailable();
        }
        final Reply reply;
        if (record != null && (!configProvider.evictProvider().evict() || useExpiredData)) {
            reply = new Reply(record.getData(), record.getSource(), configProvider.isEncrypted());
        } else {
            reply = null;
        }
        switch (configProvider.getCacheMode()) {
            case FIRST_REMOTE:
                replyObservable = getDataFromLoader(configProvider, record);
                break;
            case FIRST_CACHE:
                replyObservable = reply != null ?
                        Observable.just(reply) :
                        getDataFromLoader(configProvider, null);
                break;
            case ONLY_REMOTE:
                replyObservable = getDataFromLoader(configProvider, null);
                break;
            case ONLY_CACHE:
                if (reply != null) {
                    replyObservable = Observable.just(reply);
                } else {
                    replyObservable = Observable.error(new RxCacheException("there is no cache data to load yet"));
                }
                break;
            case CACHE_REMOTE:
                replyObservable = Observable.create(new CacheAndRemoteDataSubscribe(reply, configProvider));
                break;
            //for now ,it's same as CACHE_REMOTE
            case CACHE_REMOTE_DISTINCT:
                replyObservable = Observable.create(new CacheAndRemoteDataSubscribe(reply, configProvider));
                break;
            default:
                throw new IllegalArgumentException("the cache mode must be one of CacheMode");
        }


//        if (record != null && !configProvider.evictProvider().evict()) {
//            replyObservable = Observable.just(new Reply(record.getData(), record.getSource(), configProvider.isEncrypted()));
//        } else {
//            replyObservable = getDataFromLoader(configProvider, record);
//        }

        return (Observable<T>) replyObservable.map(new Function<Reply, Object>() {
            @Override
            public Object apply(Reply reply) throws Exception {
                return ProcessorProvidersBehaviour.this.getReturnType(configProvider, reply);
            }
        });
    }

    private Observable<Reply> getDataFromLoader(final io.rx_cache2.ConfigProvider configProvider,
                                                final Record record) {
        return configProvider.getLoaderObservable().map(new Function<Object, Reply>() {
            @Override
            public Reply apply(Object data) throws Exception {
                boolean useExpiredData = configProvider.useExpiredDataIfNotLoaderAvailable() != null ?
                        configProvider.useExpiredDataIfNotLoaderAvailable()
                        : useExpiredDataIfLoaderNotAvailable;

                if (data == null && useExpiredData && record != null) {
                    return new Reply(record.getData(), record.getSource(), configProvider.isEncrypted());
                }

                clearKeyIfNeeded(configProvider);

                if (data == null) {
                    throw new io.rx_cache2.RxCacheException(io.rx_cache2.internal.Locale.NOT_DATA_RETURN_WHEN_CALLING_OBSERVABLE_LOADER
                            + " "
                            + configProvider.getProviderKey());
                }

                twoLayersCache.save(configProvider.getProviderKey(), configProvider.getDynamicKey(),
                        configProvider.getDynamicKeyGroup(), data, configProvider.getLifeTimeMillis(),
                        configProvider.isExpirable(), configProvider.isEncrypted());
                return new Reply(data, Source.CLOUD, configProvider.isEncrypted());
            }
        }).onErrorReturn(new Function<Object, Object>() {
            @Override
            public Object apply(Object o) throws Exception {
                clearKeyIfNeeded(configProvider);

                boolean useExpiredData = configProvider.useExpiredDataIfNotLoaderAvailable() != null ?
                        configProvider.useExpiredDataIfNotLoaderAvailable()
                        : useExpiredDataIfLoaderNotAvailable;

                if (useExpiredData && record != null) {
                    return new Reply(record.getData(), record.getSource(), configProvider.isEncrypted());
                }

                throw new io.rx_cache2.RxCacheException(io.rx_cache2.internal.Locale.NOT_DATA_RETURN_WHEN_CALLING_OBSERVABLE_LOADER
                        + " "
                        + configProvider.getProviderKey(), (Throwable) o);
            }
        });
    }

    private void clearKeyIfNeeded(io.rx_cache2.ConfigProvider configProvider) {
        if (!configProvider.evictProvider().evict()) return;

        if (configProvider.evictProvider() instanceof EvictDynamicKeyGroup) {
            twoLayersCache.evictDynamicKeyGroup(configProvider.getProviderKey(),
                    configProvider.getDynamicKey().toString(),
                    configProvider.getDynamicKeyGroup().toString());
        } else if (configProvider.evictProvider() instanceof EvictDynamicKey) {
            twoLayersCache.evictDynamicKey(configProvider.getProviderKey(),
                    configProvider.getDynamicKey().toString());
        } else {
            twoLayersCache.evictProviderKey(configProvider.getProviderKey());
        }
    }

    private Object getReturnType(io.rx_cache2.ConfigProvider configProvider, Reply reply) {
        Object data = getDeepCopy.deepCopy(reply.getData());

        if (configProvider.requiredDetailedResponse()) {
            return new Reply<>(data, reply.getSource(), configProvider.isEncrypted());
        } else {
            return data;
        }
    }

    @Override
    public Observable<Void> evictAll() {
        return Observable.defer(new Callable<ObservableSource<Void>>() {
            @Override
            public ObservableSource<Void> call() throws Exception {
                ProcessorProvidersBehaviour.this.twoLayersCache.evictAll();
                return Completable.complete().toObservable();
            }
        });
    }

    private static class Transform2ReplyFunction implements Function<Object, Reply> {
        private final ConfigProvider configProvider;

        public Transform2ReplyFunction(ConfigProvider configProvider) {
            this.configProvider = configProvider;
        }

        @Override
        public Reply apply(Object data) throws Exception {
            return new Reply(data, Source.CLOUD, configProvider.isEncrypted());
        }
    }

    private static class RemoteReplyObserver extends DefaultObserver<Reply> {
        private final ObservableEmitter<Reply> emitter;
        private final boolean cacheDispatched;

        public RemoteReplyObserver(ObservableEmitter<Reply> e, boolean cacheDispatched) {
            this.emitter = e;
            this.cacheDispatched = cacheDispatched;
        }

        @Override
        public void onNext(Reply o) {
            if (emitter != null && !emitter.isDisposed()) {
                emitter.onNext(o);
                emitter.onComplete();
            }
        }

        @Override
        public void onError(Throwable e) {
            if (emitter != null && !emitter.isDisposed()) {
                if (cacheDispatched) {
                    emitter.onComplete();
                } else {
                    emitter.onError(e);
                }
            }
        }

        @Override
        public void onComplete() {

        }
    }

    private class SaveCacheConsumer implements Consumer {
        private final ConfigProvider configProvider;

        public SaveCacheConsumer(ConfigProvider configProvider) {
            this.configProvider = configProvider;
        }

        @Override
        public void accept(Object data) throws Exception {
            twoLayersCache.save(configProvider.getProviderKey(), configProvider.getDynamicKey(),
                    configProvider.getDynamicKeyGroup(), data, configProvider.getLifeTimeMillis(),
                    configProvider.isExpirable(), configProvider.isEncrypted());
        }
    }

    private class CacheAndRemoteDataSubscribe implements ObservableOnSubscribe<Reply> {
        private final Reply reply;
        private final ConfigProvider configProvider;

        public CacheAndRemoteDataSubscribe(Reply reply, ConfigProvider configProvider) {
            this.reply = reply;
            this.configProvider = configProvider;
        }

        @Override
        public void subscribe(final ObservableEmitter<Reply> e) throws Exception {
            boolean hasCache = reply != null;
            if (hasCache) {
                e.onNext(reply);
            }
            configProvider.getLoaderObservable()
                    .doOnNext(new SaveCacheConsumer(configProvider))
                    .map(new Transform2ReplyFunction(configProvider))
                    .subscribe(new RemoteReplyObserver(e, hasCache));
        }
    }
}
