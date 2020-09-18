package io.rx_cache2;

public enum CacheMode {
    //不使用缓存，该模式下,cacheKey,cacheTime 等参数均无效
    DEFAULT,
    //先请求网络，请求网络失败后再加载缓存
    FIRST_REMOTE,
    //先加载缓存，缓存没有再去请求网络
    FIRST_CACHE,
    //仅加载网络，但数据依然会被缓存
    ONLY_REMOTE,
    //只读取缓存
    ONLY_CACHE,
    //先使用缓存，不管是否存在，仍然请求网络，CallBack会回调两次.
    CACHE_REMOTE,
    //先使用缓存，不管是否存在，仍然请求网络，CallBack回调不一定是两次，如果发现请求的网络数据和缓存数据是相同的，就不会再返回网络的回调，即只回调一次。若不相同仍然会回调两次。（目的是为了防止数据没有发生变化，也需要回调两次导致界面无用的重复刷新）
    CACHE_REMOTE_DISTINCT
}
