package io.dossier.app.domain.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedSeedAdmissionModelTest {

    @Test
    fun offersAreNormalizedAndDeduplicated() {
        val model = TypedSeedAdmissionModel()
        
        assertTrue(model.offer(TypedSeedKind.Email, " USER@EXAMPLE.COM ", 1))
        // Deduplication
        assertFalse(model.offer(TypedSeedKind.Email, "user@example.com", 1))
        
        assertEquals(1, model.pendingCount)
        assertEquals(1, model.admittedCount)
        
        val popped = model.pop()
        assertEquals("user@example.com", popped?.value)
        assertEquals(TypedSeedKind.Email, popped?.kind)
        assertEquals(1, popped?.depth)
    }
    
    @Test
    fun appliesPerKindBudgets() {
        val config = TypedSeedAdmissionConfig(perKindBudgets = mapOf(TypedSeedKind.Url to 2))
        val model = TypedSeedAdmissionModel(config)
        
        assertTrue(model.offer(TypedSeedKind.Url, "https://example.com/1", 1))
        assertTrue(model.offer(TypedSeedKind.Url, "https://example.com/2", 1))
        // Budget exhausted
        assertFalse(model.offer(TypedSeedKind.Url, "https://example.com/3", 1))
        
        assertEquals(2, model.pendingCount)
    }

    @Test
    fun appliesDepthBound() {
        val model = TypedSeedAdmissionModel(TypedSeedAdmissionConfig(maxDepth = 2))
        
        assertTrue(model.offer(TypedSeedKind.Phone, "12345678", 2))
        // Exceeds depth
        assertFalse(model.offer(TypedSeedKind.Phone, "87654321", 3))
    }
    
    @Test
    fun limitsTotalBudget() {
        val config = TypedSeedAdmissionConfig(maxTotalSeeds = 2, perKindBudgets = mapOf(TypedSeedKind.Url to 5))
        val model = TypedSeedAdmissionModel(config)
        
        assertTrue(model.offer(TypedSeedKind.Url, "https://example.com/1", 1))
        assertTrue(model.offer(TypedSeedKind.Url, "https://example.com/2", 1))
        // Total exhausted
        assertFalse(model.offer(TypedSeedKind.Url, "https://example.com/3", 1))
    }
}
