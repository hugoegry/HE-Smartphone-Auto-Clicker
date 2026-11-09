package com.he.he_smartphone_auto_clicker.model

enum class ClickerMode { NONE, SINGLE, MULTI }
enum class ActionKind { CLICK, DOUBLE_CLICK, SWIPE, PINCH, MULTI_TOUCH, JOYSTICK }
enum class GroupRunMode { SEQUENCE, RANDOM, ON_DEMAND }

enum class DetectorPause { NONE, ALL, ACTION }

data class ActionGroup(
    val id: Long,
    val name: String,
    val mode: GroupRunMode,
    val main: Boolean = false,
    val visible: Boolean = true,
    val delaySec: Int = 0
)

data class ClickerAction(
    val id: Long,
    val kind: ActionKind,
    val x: Float,
    val y: Float,
    val x2: Float,
    val y2: Float,
    val durationMs: Long,
    val delayMs: Long,
    val groupId: Long,
    val option: Int = 0,
    val holdMs: Long = 0,
    val radius: Float = 0.10f,
    val angleFrom: Float = 0f,
    val angleTo: Float = 360f,
    val pauseAllWhileAction: Boolean = false,
    val pauseAllWhileDelay: Boolean = false
) {
    fun isTwoPoint(): Boolean = kind == ActionKind.SWIPE || kind == ActionKind.PINCH || kind == ActionKind.MULTI_TOUCH
    fun hasGuideLine(): Boolean = kind == ActionKind.SWIPE || kind == ActionKind.PINCH
    fun isPinchOut(): Boolean = kind == ActionKind.PINCH && option == 1
    fun joyMode(): Int = if (kind == ActionKind.JOYSTICK) option.coerceIn(0, 2) else 0
}

data class Detector(
    val id: Long,
    val name: String,
    val enabled: Boolean = true,
    val tplW: Int = 0,
    val tplH: Int = 0,
    val zoneL: Float = 0f,
    val zoneT: Float = 0f,
    val zoneR: Float = 1f,
    val zoneB: Float = 1f,
    val threshold: Int = 85,
    val delaySec: Int = 0,
    val groupId: Long = 0L,
    val pause: DetectorPause = DetectorPause.ACTION,
    val repeatSec: Int = 0,
    val query: String = ""
) {
    fun isText(): Boolean = query.isNotBlank()
}

data class ClickerPreset(
    val id: Long,
    val name: String,
    val singlePressMs: Long,
    val singleDelayMs: Long,
    val singleX: Float,
    val singleY: Float,
    val defaultPressMs: Long,
    val defaultDelayMs: Long,
    val defaultSwipeMs: Long,
    val repeatCount: Int,
    val startDelaySec: Int = 0,
    val groups: List<ActionGroup>,
    val actions: List<ClickerAction>,
    val detectors: List<Detector> = emptyList()
)
