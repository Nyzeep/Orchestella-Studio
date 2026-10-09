package io.github.nyzeep.orchestella;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Lightweight in-memory SharedPreferences implementation for JVM unit tests. */
public final class FakeSharedPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Override
    public String getString(String key, String defValue) {
        Object val = values.get(key);
        return val instanceof String ? (String) val : defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object val = values.get(key);
        return val instanceof Integer ? (Integer) val : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object val = values.get(key);
        return val instanceof Long ? (Long) val : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object val = values.get(key);
        return val instanceof Float ? (Float) val : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object val = values.get(key);
        return val instanceof Boolean ? (Boolean) val : defValue;
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    private final class FakeEditor implements Editor {
        private final Map<String, Object> temp = new HashMap<>(values);

        @Override
        public Editor putString(String key, String value) {
            temp.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            temp.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            temp.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            temp.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            temp.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            temp.remove(key);
            return this;
        }

        @Override
        public Editor clear() {
            temp.clear();
            return this;
        }

        @Override
        public boolean commit() {
            values.clear();
            values.putAll(temp);
            return true;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
