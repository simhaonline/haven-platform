/*
 * Copyright 2016 Code Above Lab LLC
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

package com.codeabovelab.dm.common.kv.mapping;

import com.codeabovelab.dm.common.kv.KvStorageEvent;
import com.codeabovelab.dm.common.kv.KvUtils;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import lombok.Data;
import org.springframework.util.Assert;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * KeyValue storage map of directory. <p/>
 * It has internal cache on plain map, without timeouts, and also update it on KV events. We do not using
 * guava cache because want to add keys into map without loading values.
 */
public class KvMap<T> {

    @Data
    public static class Builder<T, V> {
        private KvMapperFactory mapper;
        private String path;
        private KvMapAdapter<T> adapter = KvMapAdapter.direct();
        private final Class<T> type;
        private final Class<V> valueType;
        /**
         * Note that it invoke at events caused by map user. For events from KV storage use {@link #setListener(Consumer)}.
         */
        private Consumer<KvMapEvent<T>> consumer;
        /**
         * Note that it handle events from KV storage. For events caused by map user use {@link #setConsumer(Consumer)} .
         */
        private Consumer<KvMapEvent<T>> listener;
        private KvObjectFactory<T> factory;

        public Builder<T, V> mapper(KvMapperFactory factory) {
            setMapper(factory);
            return this;
        }

        public Builder<T, V> path(String path) {
            setPath(path);
            return this;
        }

        public Builder<T, V> adapter(KvMapAdapter<T> adapter) {
            setAdapter(adapter);
            return this;
        }

        /**
         * Note that it invoke at events caused by map user. For events from KV storage use {@link #setListener(Consumer)}.
         * @param consumer handler for local events causet by invoking of map methods
         * @return this
         */
        public Builder<T, V> consumer(Consumer<KvMapEvent<T>> consumer) {
            setConsumer(consumer);
            return this;
        }

        /**
         * Note that it handle events from KV storage. For events caused by map user use {@link #setConsumer(Consumer)} .
         * @param listener handler for KV storage events.
         * @return this
         */
        public Builder<T, V> listener(Consumer<KvMapEvent<T>> listener) {
            setListener(listener);
            return this;
        }

        public Builder<T, V> factory(KvObjectFactory<T> factory) {
            setFactory(factory);
            return this;
        }

        public KvMap<T> build() {
            Assert.notNull(type);
            return new KvMap<>(this);
        }
    }
    private final  class ValueHolder {
        private final String key;
        private volatile T value;
        private final Map<String, Long> index = new ConcurrentHashMap<>();
        private volatile boolean dirty = true;

        ValueHolder(String key) {
            Assert.notNull(key, "key is null");
            this.key = key;
        }

        synchronized T save(T val) {
            checkValue(val);
            this.dirty = false;
            if(val == value) {
                return value;
            }
            T old = this.value;
            this.value = val;
            onSave(this, old == null? KvStorageEvent.Crud.CREATE : KvStorageEvent.Crud.UPDATE);
            flush();
            return old;
        }

        synchronized void flush() {
            if(this.value == null) {
                // no value set, nothing to flush
                return;
            }
            this.dirty = false;
            Object obj = adapter.get(this.key, this.value);

            Assert.notNull(obj, "Adapter " + adapter + " return null from " + this.value + " that is not allowed");
            mapper.save(key, obj, (p, res) -> {
                index.put(p.getKey(), res.getIndex());
            });
        }

        synchronized void dirty(String prop, long newIndex) {
            Long old = this.index.get(prop);
            if(old != null && old != newIndex) {
                dirty();
            }
        }

        synchronized void dirty() {
            this.dirty = true;
        }

        synchronized T get() {
            if(dirty) {
                load();
            }
            return value;
        }

        synchronized void set(T value) {
            checkValue(value);
            internalSet(value);
        }

        private void internalSet(T value) {
            this.dirty = false;
            this.value = value;
        }

        private void checkValue(T value) {
            Assert.notNull(value, "Null value is not allowed");
        }

        synchronized void load() {
            Object obj = mapper.load(key, adapter.getType(this.value));
            T newVal = null;
            if(obj != null || this.value != null) {
                newVal = adapter.set(this.key, this.value, obj);
                if(newVal == null) {
                    throw new IllegalStateException("Adapter " + adapter + " broke contract: it return null value for non null object.");
                }
            }
            internalSet(newVal);
        }

        synchronized T getIfPresent() {
            if(dirty) {
                // returning dirty value may cause unexpected effects
                return null;
            }
            return value;
        }

        synchronized T computeIfAbsent(Function<String, T> func) {
            get(); // we must try to load before compute
            if(value == null) {
                save(func.apply(key));
            }
            // get - is load value if its present, but dirty
            return get();
        }
    }

    private final KvClassMapper<Object> mapper;
    private final KvMapAdapter<T> adapter;
    private final Consumer<KvMapEvent<T>> consumer;
    private final Consumer<KvMapEvent<T>> listener;
    private final ConcurrentMap<String, ValueHolder> map = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private KvMap(Builder builder) {
        this.adapter = builder.adapter;
        this.consumer = builder.consumer;
        this.listener = builder.listener;
        Class<Object> mapperType = MoreObjects.firstNonNull(builder.valueType, (Class<Object>)builder.type);
        this.mapper = builder.mapper.buildClassMapper(mapperType)
          .prefix(builder.path)
          .factory(builder.factory)
          .build();
        builder.mapper.getStorage().subscriptions().subscribeOnKey(this::onKvEvent, builder.path);
    }

    /**
     * Load all data.
     */
    public void load() {
        this.mapper.list().forEach(this::getOrCreateHolder);
    }

    public static <T> Builder<T, T> builder(Class<T> type) {
        return new Builder<>(type, type);
    }

    public static <T, V> Builder<T, V> builder(Class<T> type, Class<V> value) {
        return new Builder<>(type, value);
    }

    private void onKvEvent(KvStorageEvent e) {
        String path = e.getKey();
        final long index = e.getIndex();
        KvStorageEvent.Crud action = e.getAction();
        String key = this.mapper.getName(path);
        if(key == null) {
            if(action == KvStorageEvent.Crud.DELETE) {
                Set<String> set = new HashSet<>(map.keySet());
                // it meat that someone remove mapped node with all entries, we must clear map
                // note that current implementation does not support consistency
                set.forEach((removedKey) -> {
                    ValueHolder holder = map.remove(removedKey);
                    invokeListener(KvStorageEvent.Crud.DELETE, removedKey, holder);
                });
            }
            return;
        }
        String property = KvUtils.child(this.mapper.getPrefix(), path, 1);
        ValueHolder holder = null;
        if(property != null) {
            holder = getOrCreateHolder(key);
            holder.dirty(property, index);
        } else {
            switch (action) {
                case CREATE:
                    // we use lazy loading
                    holder = getOrCreateHolder(key);
                    break;
                case READ:
                    //ignore
                    break;
                case UPDATE:
                    holder = getOrCreateHolder(key);
                    holder.dirty();
                    break;
                case DELETE:
                    holder = map.remove(key);
            }
        }
        invokeListener(action, key, holder);
    }

    private void invokeListener(KvStorageEvent.Crud action, String key, ValueHolder holder) {
        if(listener != null) {
            T value = null;
            if(holder != null) {
                // we cah obtain value before it become dirty, but it will wrong behavior
                value = holder.getIfPresent();
            }
            listener.accept(new KvMapEvent<>(this, action, key, value));
        }
    }

    private void onSave(ValueHolder holder, KvStorageEvent.Crud action) {
        if(consumer == null) {
            return;
        }
        KvMapEvent<T> event = new KvMapEvent<>(this, action, holder.key, holder.getIfPresent());
        consumer.accept(event);
    }

    /**
     * Get exists or load value from storage.
     * @param key key
     * @return value or null if not exists
     */
    public T get(String key) {
        // we can not use computeIfAbsent here because load code can modify map
        ValueHolder holder = getOrCreateHolder(key);
        T val = holder.get();
        if(val == null) {
            map.remove(key, holder);
            return null;
        }
        return holder.get();
    }

    /**
     * Get exists value from storage. Not load it, even if dirty.
     * @param key key
     * @return value or null if not exists or dirty.
     */
    public T getIfPresent(String key) {
        ValueHolder holder = map.get(key);
        if(holder == null) {
            return null;
        }
        return holder.getIfPresent();
    }

    /**
     * Put and save value into storage.
     * @param key key
     * @param val value
     * @return old value if exists
     */
    public T put(String key, T val) {
        ValueHolder holder = getOrCreateHolder(key);
        return holder.save(val);
    }

    /**
     * Remove value directly from storage, from map it will be removed at event.
     *
     * @param key key for remove
     * @return gives value only if present, not load it, this mean that you may obtain null, event storage has value
     */
    public T remove(String key) {
        ValueHolder valueHolder = map.get(key);
        mapper.delete(key);
        if (valueHolder != null) {
            // we must not load value
            return valueHolder.getIfPresent();
        }
        return null;
    }

    public T computeIfAbsent(String key, Function<String, T> func) {
        ValueHolder holder = getOrCreateHolder(key);
        return holder.computeIfAbsent(func);
    }

    /**
     * Save existed value of specified key. If is not present, do nothing.
     * @param key key of value.
     */
    public void flush(String key) {
        ValueHolder holder = map.get(key);
        if(holder != null) {
            holder.flush();
        }
    }

    private ValueHolder getOrCreateHolder(String key) {
        ValueHolder holder = new ValueHolder(key);
        ValueHolder old = map.putIfAbsent(key, holder);
        if(old != null) {
            holder = old;
        }
        return holder;
    }

    /**
     * Immutable set of keys.
     * @return set of keys, never null.
     */
    public Set<String> list() {
        return ImmutableSet.copyOf(this.map.keySet());
    }

    /**
     * Load all values of map. Note that it may cause time consumption.
     * @return immutable collection of values
     */
    public Collection<T> values() {
        ImmutableList.Builder<T> b = ImmutableList.builder();
        this.map.values().forEach(valueHolder -> {
            T element = valueHolder.get();
            // map does not contain holders with null elements, but sometime it happen
            // due to multithread access , for example in `put()` method
            if(element != null) {
                b.add(element);
            }
        });
        return b.build();
    }

    public void forEach(BiConsumer<String, ? super T> action) {
        this.map.forEach((key, holder) -> {
            T value = holder.get();
            if(value != null) {
                action.accept(key, value);
            }
        });
    }
}