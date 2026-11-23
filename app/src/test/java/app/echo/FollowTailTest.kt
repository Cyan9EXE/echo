package app.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
