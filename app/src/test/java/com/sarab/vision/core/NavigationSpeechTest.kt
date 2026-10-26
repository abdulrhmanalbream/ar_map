package com.sarab.vision.core

import org.junit.Assert.*
import org.junit.Test

class NavigationSpeechTest {
    @Test fun directionBoundariesAgreeWithExistingVisualAndVoicePolicy() {
        val cases = mapOf(-180.0 to NavigationDirection.BACK, -125.01 to NavigationDirection.BACK,
            -125.0 to NavigationDirection.LEFT, -55.01 to NavigationDirection.LEFT,
            -55.0 to NavigationDirection.SLIGHT_LEFT, -15.01 to NavigationDirection.SLIGHT_LEFT,
            -15.0 to NavigationDirection.FORWARD, 0.0 to NavigationDirection.FORWARD,
            15.0 to NavigationDirection.FORWARD, 15.01 to NavigationDirection.SLIGHT_RIGHT,
            55.0 to NavigationDirection.SLIGHT_RIGHT, 55.01 to NavigationDirection.RIGHT,
            125.0 to NavigationDirection.RIGHT, 125.01 to NavigationDirection.BACK, 180.0 to NavigationDirection.BACK)
        for ((degrees, expected) in cases) {
            val mode = GuidanceMode.Compass(163.0, 0.0, degrees)
            assertEquals(expected, navigationDirection(degrees))
            val plan = navigationSpeechPlan(mode, "وجهة")
            assertEquals(expected.clipId, (plan[0] as NavigationSpeechSegment.Clip).id)
            val identity = when (expected) {
                NavigationDirection.FORWARD -> "امشِ للأمام"
                NavigationDirection.RIGHT -> "اتجه يميناً"
                NavigationDirection.LEFT -> "اتجه يساراً"
                NavigationDirection.BACK -> "استدر للخلف"
                NavigationDirection.SLIGHT_RIGHT -> "مِل يميناً قليلاً"
                NavigationDirection.SLIGHT_LEFT -> "مِل يساراً قليلاً"
            }
            assertTrue(voiceCueAr(mode, "وجهة")!!.startsWith(identity))
            assertTrue(instructionAr(mode, "وجهة").startsWith(identity))
        }
    }

    @Test fun distanceClipsFollowExistingBucketsAcrossStepBoundaries() {
        val cases = mapOf(30.0 to 25, 45.0 to 50, 80.0 to 75, 99.0 to 100,
            100.0 to 100, 163.0 to 150, 240.0 to 250, 299.0 to 300,
            300.0 to 300, 480.0 to 500, 1999.0 to 2000)
        for ((distance, bucket) in cases) {
            assertEquals("nav_ar_distance_$bucket", (navigationDistanceSpeech(distance) as NavigationSpeechSegment.Clip).id)
        }
        assertEquals(25, NAVIGATION_DISTANCE_CLIPS.size)
        assertEquals(36, NAVIGATION_CLIP_IDS.size)
    }

    @Test fun rareDistanceKeepsFullSpokenMetresWithoutClampingToLastRecording() {
        val segment = navigationDistanceSpeech(2160.0) as NavigationSpeechSegment.Text
        assertEquals("المسافة المتبقية، 2200 متراً.", segment.text)
        assertFalse(segment.text.contains("كم"))
    }

    @Test fun arrivalApproachAndSelectionKeepTheActualDestinationName() {
        val target = "بوابة أضافها المستخدم"
        val plans = listOf(navigationSpeechPlan(GuidanceMode.Arrived(4.0), target),
            navigationSpeechPlan(GuidanceMode.ArApproach(18.0), target), navigationSelectionSpeech(target))
        for (plan in plans) {
            assertTrue(plan.first() is NavigationSpeechSegment.Clip)
            assertEquals(NavigationSpeechSegment.Text(target), plan.last())
        }
    }

    @Test fun ambiguityNeverSubstitutesAnArrivalOrSpeaksAnUnconfirmedName() {
        val plan = navigationSpeechPlan(GuidanceMode.Ambiguous(4.0, emptyList()), "غير مؤكد")
        assertEquals(1, plan.size)
        assertEquals("nav_ar_ambiguous", (plan.single() as NavigationSpeechSegment.Clip).id)
    }

    @Test fun explicitRepeatUsesVoiceDistanceAndNoFixDoesNotNagAutomatically() {
        assertTrue(navigationSpeechPlan(GuidanceMode.NoFix, "وجهة").isEmpty())
        assertEquals("nav_ar_no_fix", (navigationSpeechPlan(GuidanceMode.NoFix, "وجهة", true).single() as NavigationSpeechSegment.Clip).id)
        val plan = navigationSpeechPlan(GuidanceMode.Compass(163.0, 0.0, 90.0), "وجهة", true)
        assertEquals("nav_ar_right", (plan[0] as NavigationSpeechSegment.Clip).id)
        assertEquals("nav_ar_distance_150", (plan[1] as NavigationSpeechSegment.Clip).id)
    }

}
