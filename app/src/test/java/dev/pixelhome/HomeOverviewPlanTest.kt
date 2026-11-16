package dev.pixelhome

import org.junit.Assert.*
import org.junit.Test

class HomeOverviewPlanTest {
    @Test fun typicalTabletShowsFourFavoritesInTwoRows() {
        val plan=homeOverviewPlan(1248f,640f,1f,8,true)
        assertTrue(plan.landscape);assertEquals(2,plan.columns);assertEquals(2,plan.rows);assertEquals(4,plan.visibleFavorites)
    }
    @Test fun largeTextPreservesTargetsByReducingCapacity() {
        val plan=homeOverviewPlan(1248f,600f,2f,8,true)
        assertEquals(1,plan.rows);assertEquals(2,plan.visibleFavorites)
    }
    @Test fun portraitReservesMusicSpaceWithoutASecondPage() {
        val plan=homeOverviewPlan(768f,1120f,1f,4,true)
        assertFalse(plan.landscape);assertEquals(260f,plan.musicHeight);assertEquals(4,plan.visibleFavorites)
    }
    @Test fun hiddenMusicDoesNotReserveEmptySpace() {assertEquals(0f,homeOverviewPlan(768f,1120f,1f,4,false).musicHeight)}
    @Test fun emptyAndOddListsDoNotInventDevices() {
        assertEquals(0,homeOverviewPlan(1248f,640f,1f,0,true).visibleFavorites)
        assertEquals(3,homeOverviewPlan(1248f,640f,1f,3,true).visibleFavorites)
    }
    @Test fun constrainedSizeNeverCreatesNegativeOrUnboundedCapacity() {
        for(scale in listOf(.5f,1f,2f,3f)) for(count in listOf(-1,0,1,8,100)) {
            val p=homeOverviewPlan(400f,500f,scale,count,true)
            assertTrue(p.visibleFavorites in 0..2);assertTrue(p.rows in 1..2)
        }
    }
}
