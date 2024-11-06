package com.google.projectgameface.utils

import kotlin.reflect.KProperty

class TimestampDelegate<T>(private var initialValue: T) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return initialValue
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        initialValue = value
        (thisRef as? TimestampAware)?.updateTimestamp()
    }
}

interface TimestampAware {
    fun updateTimestamp()
}
