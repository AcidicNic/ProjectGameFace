package com.google.projectgameface.utils

enum class ActionStatus {
    NONE,
    STARTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED;

    companion object {
        fun fromString(status: String): ActionStatus {
            return when (status.uppercase()) {
                "STARTED" -> STARTED
                "IN_PROGRESS" -> IN_PROGRESS
                "COMPLETED" -> COMPLETED
                "FAILED" -> FAILED
                else -> NONE
            }
        }
    }
}