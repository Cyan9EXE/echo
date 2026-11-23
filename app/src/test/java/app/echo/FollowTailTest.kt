package app.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 跟底判定的纯逻辑回归：新消息插入后必须能滚到新尾，不能停在旧尾（P1-1）。 */
class FollowTailTest {
    @Test
    fun rescuesWhenNewTailInserted() {
        // 发送一条消息后新增 user + assistant 两项：旧可见尾 index=2，新总数=7。
        assertEquals(6, tailRescueIndex(2, 7))
    }

    @Test
    fun rescuesFromPreviousTailAfterAppend() {
        // 已经停在旧尾（index=4），又追加一条：必须继续追到 5。
        assertEquals(5, tailRescueIndex(4, 6))
    }

    @Test
    fun noRescueWhenAlreadyAtTail() {
        assertNull(tailRescueIndex(4, 5))
    }

    @Test
    fun noRescueForEmptyList() {
        assertNull(tailRescueIndex(-1, 0))
        assertNull(tailRescueIndex(0, 0))
    }

    @Test
    fun followTailAfterScrollStops() {
        // 首帧/空列表不能把 followTail 置 false（P1-1）。
        assertTrue(shouldFollowTail(null, 0, 0f))
        // 停在最后一项末端才恢复；差 3px 就视为离开底部（R4-P1-2）。
        assertTrue(shouldFollowTail(4, 5, 0f))
        assertTrue(shouldFollowTail(4, 5, 2f))
        assertFalse(shouldFollowTail(4, 5, 3f))
        // 不在最后一项上永远不恢复。
        assertFalse(shouldFollowTail(3, 5, 0f))
    }

    @Test
    fun sendScrollTargetAlwaysLastItem() {
        // 发送成功后无论此前滚到哪里，目标都是最后一条（P2-1）。
        assertEquals(4, sendScrollTarget(5))
        assertEquals(0, sendScrollTarget(1))
        assertNull(sendScrollTarget(0))
    }
}
