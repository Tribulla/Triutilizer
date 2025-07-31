package com.triutilizer;

import java.util.*;
import java.lang.reflect.Method;

public class FerriteSupport {
    private static Method shareMethod;
    private static boolean initializationAttempted = false;

    private static void initializeFerriteCore() {
        if (initializationAttempted) return;
        initializationAttempted = true;

        try {
            Class<?> dynamicSharingClass = Class.forName("malte0811.ferritecore.api.DynamicSharing");
            shareMethod = dynamicSharingClass.getMethod("share", Object.class);
        } catch (Exception e) {
            // FerriteCore not present, that's okay
            shareMethod = null;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T shareObject(T object) {
        if (object == null) return null;
        
        if (!initializationAttempted) {
            initializeFerriteCore();
        }

        if (shareMethod == null) {
            return object;  // FerriteCore not present, return original object
        }

        try {
            return (T)shareMethod.invoke(null, object);
        } catch (Exception e) {
            return object;  // If anything fails, return original object
        }
    }
    
    public static <T> void optimizeBatch(List<T> batch) {
        if (batch == null) return;
        Set<T> uniqueObjects = new HashSet<>();
        for (int i = 0; i < batch.size(); i++) {
            T obj = batch.get(i);
            T shared = shareObject(obj);
            if (shared != obj) {
                batch.set(i, shared);
            }
            uniqueObjects.add(shared);
        }
    }
}
