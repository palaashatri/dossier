package io.dossier.app.domain.scanner

import android.content.Context
import android.net.Uri
import io.dossier.app.domain.ai.LocalAiModelType
import io.dossier.app.data.local.ProfileConsistencyCache
import io.dossier.app.domain.model.*
import io.dossier.app.domain.pii.PiiExtractor
import io.dossier.app.domain.remediation.RemediationProvider
import io.dossier.app.domain.risk.RiskScorer
import io.dossier.app.domain.username.UsernameVariantGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

object ScanSession {
    var tempInput: IdentityInput? = null
    val selectedModel = MutableStateFlow(LocalAiModelType.DEFAULT)

    private val _currentInput = MutableStateFlow<IdentityInput?>(null)
    val currentInput: StateFlow<IdentityInput?> = _currentInput

    private val _findings = MutableStateFlow<List<Finding>>(emptyList())
    val findings: StateFlow<List<Finding>> = _findings

    private val _placeScanResult = MutableStateFlow<PlaceScanResult?>(null)
    val placeScanResult: StateFlow<PlaceScanResult?> = _placeScanResult

    private val _profileScanResults = MutableStateFlow<List<ProfileScanResult>>(emptyList())
    val profileScanResults: StateFlow<List<ProfileScanResult>> = _profileScanResults


    private val _faceConsistencyMatches = MutableStateFlow<List<FaceConsistencyMatch>>(emptyList())
    val faceConsistencyMatches: StateFlow<List<FaceConsistencyMatch>> = _faceConsistencyMatches

    private val _riskLevel = MutableStateFlow(RiskLevel.Low)
    val riskLevel: StateFlow<RiskLevel> = _riskLevel

    private val _remediationTips = MutableStateFlow<List<String>>(emptyList())
    val remediationTips: StateFlow<List<String>> = _remediationTips

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _progressText = MutableStateFlow("")
    val progressText: StateFlow<String> = _progressText

    // Persistent, observable Deep Research toggle — shown on all search panels
    // (Identity + Reverse Image Lookup). When on, the scan/lookup follows linked
    // personal sites and mines richer context. Unlike the old one-shot flag, this
    // stays set across re-runs until the user turns it off.
    private val _deepResearchEnabled = MutableStateFlow(false)
    val deepResearchEnabled: StateFlow<Boolean> = _deepResearchEnabled

    fun toggleDeepResearch() {
        _deepResearchEnabled.value = !_deepResearchEnabled.value
    }

    fun setDeepResearch(enabled: Boolean) {
        _deepResearchEnabled.value = enabled
    }

    private var placeImageUri: Uri? = null

    fun setPlaceImage(uri: Uri?) {
        placeImageUri = uri
    }

    fun getPlaceImage(): Uri? = placeImageUri

    suspend fun executeScan(context: Context, input: IdentityInput, deepResearch: Boolean = false) = withContext(Dispatchers.IO) {
        // HONESTY: no mock selfie is generated. If the user didn't supply one,
        // face comparison is skipped entirely (the report shows an honest
        // "no selfie provided" message instead of fabricating one).
        val inputToUse = input

        _currentInput.value = inputToUse
        _isScanning.value = true
        _findings.value = emptyList()
        _placeScanResult.value = null
        _profileScanResults.value = emptyList()
        _faceConsistencyMatches.value = emptyList()
        _riskLevel.value = RiskLevel.Low
        _remediationTips.value = emptyList()


        val cache = ProfileConsistencyCache(context)
        cache.clearAll()

        try {
            _progressText.value = "DISCOVERING_USERNAMES..."
            val piiExtractor = PiiExtractor()
            val variantGenerator = UsernameVariantGenerator()
            val profileScanner = ProfileScanner(context, piiExtractor, variantGenerator)
            
            val scanResults = profileScanner.scanIdentity(inputToUse, deepResearch = deepResearch)
            _profileScanResults.value = scanResults
            
            val allFindings = mutableListOf<Finding>()
            scanResults.forEach { result ->
                if (result.exists) {
                    allFindings.addAll(result.findings)
                }
            }


            // Face consistency is SKIPPED honestly: there is no embedding model
            // and no real profile-image scraping in this build, so any "similarity
            // score" would be fabricated. The ReportScreen shows an honest message
            // instead. (A real FaceNet model + profile avatar scraping would be
            // needed to restore this; until then, no fake scores.)
            _faceConsistencyMatches.value = emptyList()

            _progressText.value = "COMPILING_EXPOSURE_LEVELS..."
            val riskScorer = RiskScorer()
            val overallRisk = riskScorer.score(allFindings)
            _riskLevel.value = overallRisk

            val remediationProvider = RemediationProvider()
            _remediationTips.value = remediationProvider.getGlobalTips(allFindings)

            _findings.value = allFindings.distinctBy { it.type.name + it.value + it.sourceUrl }
        } catch (e: Exception) {
            e.printStackTrace()
            // Surface a diagnostic so a scan failure is never silently empty.
            // Whatever partial results exist are kept; this message tells the
            // user the scan hit an error rather than genuinely finding nothing.
            if (_findings.value.isEmpty() && _profileScanResults.value.isEmpty()) {
                _findings.value = listOf(
                    Finding(
                        type = FindingType.SensitiveSnippet,
                        value = "Scan interrupted",
                        sourceUrl = null,
                        evidenceSnippet = "The scan hit an unexpected error: ${e.localizedMessage ?: e.javaClass.simpleName}. Partial results, if any, are shown. Re-run or try Deep Research.",
                        confidence = 1.0f,
                        risk = RiskLevel.Medium,
                        remediation = "Check network connectivity and retry. If it persists, the target site may be blocking automated access."
                    )
                )
            }
        } finally {
            _isScanning.value = false
            cache.close()
        }
    }

    fun purgeSession(context: Context) {
        _currentInput.value = null
        _findings.value = emptyList()
        _placeScanResult.value = null
        _profileScanResults.value = emptyList()
        _faceConsistencyMatches.value = emptyList()
        _riskLevel.value = RiskLevel.Low
        _remediationTips.value = emptyList()
        placeImageUri = null

        
        val cache = ProfileConsistencyCache(context)
        cache.clearAll()
        cache.close()
    }
}
