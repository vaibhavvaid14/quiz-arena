package quizarena.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the API client does when the server is not there. The happy paths are
 * covered end to end by the browser suite, which drives the real UI against a
 * real server; this is about the failure that suite cannot stage.
 */
class ApiTest {
    @Test
    fun anUnreachableServerBecomesAReadableOfflineError() = runTest {
        // Port 9 is the discard service: nothing listens, so fetch rejects.
        val api = Api(baseUrl = "http://127.0.0.1:9")
        try {
            api.catalog()
            fail("expected the call to fail")
        } catch (error: ApiError) {
            assertEquals(0, error.status)
            assertEquals("offline", error.code)
            assertTrue(error.isOffline)
            assertTrue(
                error.message!!.contains("reach the quiz server"),
                "the message should be showable to a player: ${error.message}",
            )
        }
    }
}
