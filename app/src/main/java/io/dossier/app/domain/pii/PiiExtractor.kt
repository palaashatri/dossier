package io.dossier.app.domain.pii

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel

import io.dossier.app.domain.model.IdentityInput

class PiiExtractor {
    private val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    private val phoneRegex = Regex("\\+?\\d{1,4}?[-.\\s]?\\(?\\d{1,3}?\\)?[-.\\s]?\\d{1,4}[-.\\s]?\\d{1,4}[-.\\s]?\\d{1,9}")
    private val locationRegex = Regex("\\b(?:lives in|Lives in|based in|Based in|located in|Located in|from|From)\\s+([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)*)\\b")
    private val orgRegex = Regex("\\b(?:works at|Works at|studied at|Studied at|employed at|Employed at|member of|Member of|developer at|Developer at|engineer at|Engineer at|student at|Student at|designer at|Designer at|lead at|Lead at|intern at|Intern at|manager at|Manager at)\\s+([A-Z][a-zA-Z0-9]+(?:\\s+[A-Z][a-zA-Z0-9]+)*)\\b")

    fun extract(text: String, sourceUrl: String, identity: IdentityInput? = null): List<Finding> {
        val findings = mutableListOf<Finding>()

        // 1. Email Extraction
        emailRegex.findAll(text).forEach { match ->
            findings.add(
                Finding(
                    type = FindingType.Email,
                    value = match.value,
                    sourceUrl = sourceUrl,
                    evidenceSnippet = getSnippet(text, match.range),
                    confidence = 0.95f,
                    risk = RiskLevel.High,
                    remediation = "Remove public email visibility or use a masked email alias."
                )
            )
        }

        // 2. Phone Extraction
        phoneRegex.findAll(text).forEach { match ->
            val digitsOnly = match.value.filter { it.isDigit() }
            if (digitsOnly.length in 8..15) {
                findings.add(
                    Finding(
                        type = FindingType.Phone,
                        value = match.value,
                        sourceUrl = sourceUrl,
                        evidenceSnippet = getSnippet(text, match.range),
                        confidence = 0.85f,
                        risk = RiskLevel.Critical,
                        remediation = "Delete phone number from public bio or enable strict privacy settings."
                    )
                )
            }
        }

        // 3. Location Extraction
        locationRegex.findAll(text).forEach { match ->
            val rawValue = match.groupValues[1].trim()
            val fullMatch = match.value
            val detectedType = determineTypeForLocationMatch(rawValue, fullMatch, identity)
            findings.add(
                Finding(
                    type = detectedType,
                    value = rawValue,
                    sourceUrl = sourceUrl,
                    evidenceSnippet = fullMatch,
                    confidence = 0.75f,
                    risk = if (detectedType == FindingType.Location) RiskLevel.Medium else RiskLevel.Low,
                    remediation = if (detectedType == FindingType.Location) {
                        "Avoid listing specific location details in public bio descriptions."
                    } else {
                        "Remove company associations to prevent spear-phishing or social engineering."
                    }
                )
            )
        }

        // 4. Organization Extraction
        orgRegex.findAll(text).forEach { match ->
            val rawValue = match.groupValues[1].trim()
            val fullMatch = match.value
            val detectedType = determineTypeForOrgMatch(rawValue, fullMatch, identity)
            findings.add(
                Finding(
                    type = detectedType,
                    value = rawValue,
                    sourceUrl = sourceUrl,
                    evidenceSnippet = fullMatch,
                    confidence = 0.70f,
                    risk = if (detectedType == FindingType.Location) RiskLevel.Medium else RiskLevel.Low,
                    remediation = if (detectedType == FindingType.Location) {
                        "Avoid listing specific location details in public bio descriptions."
                    } else {
                        "Remove company associations to prevent spear-phishing or social engineering."
                    }
                )
            )
        }

        // --- SPECIFIC IDENTITY MATCHING (Direct Exposure Detection) ---
        if (identity != null) {
            // A. Full Name Exposure Check
            val trimmedName = identity.fullName.trim()
            if (trimmedName.isNotBlank()) {
                val parts = trimmedName.split("\\s+".toRegex()).map { Regex.escape(it) }
                val nameRegexPattern = "\\b" + parts.joinToString("\\s+") + "\\b"
                val nameRegex = Regex(nameRegexPattern, RegexOption.IGNORE_CASE)
                nameRegex.findAll(text).forEach { match ->
                    findings.add(
                        Finding(
                            type = FindingType.SensitiveSnippet,
                            value = "Name Exposure: $trimmedName",
                            sourceUrl = sourceUrl,
                            evidenceSnippet = getSnippet(text, match.range),
                            confidence = 0.98f,
                            risk = RiskLevel.High,
                            remediation = "Remove real name from public profile headers, usernames, or bio contents."
                        )
                    )
                }
            }

            // B. Aliases Exposure Check
            identity.aliases.forEach { alias ->
                val trimmedAlias = alias.trim()
                if (trimmedAlias.isNotBlank()) {
                    val aliasRegex = Regex("\\b${Regex.escape(trimmedAlias)}\\b", RegexOption.IGNORE_CASE)
                    aliasRegex.findAll(text).forEach { match ->
                        findings.add(
                            Finding(
                                type = FindingType.SensitiveSnippet,
                                value = "Alias Exposure: $trimmedAlias",
                                sourceUrl = sourceUrl,
                                evidenceSnippet = getSnippet(text, match.range),
                                confidence = 0.85f,
                                risk = RiskLevel.Medium,
                                remediation = "Change or dissociate known aliases to reduce correlation mapping."
                            )
                        )
                    }
                }
            }

            // C. Direct Location hits
            identity.locations.forEach { location ->
                val trimmedLoc = location.trim()
                if (trimmedLoc.isNotBlank() && !findings.any { it.type == FindingType.Location && it.value.contains(trimmedLoc, ignoreCase = true) }) {
                    val locRegex = Regex("\\b${Regex.escape(trimmedLoc)}\\b", RegexOption.IGNORE_CASE)
                    locRegex.findAll(text).forEach { match ->
                        findings.add(
                            Finding(
                                type = FindingType.Location,
                                value = trimmedLoc,
                                sourceUrl = sourceUrl,
                                evidenceSnippet = getSnippet(text, match.range),
                                confidence = 0.90f,
                                risk = RiskLevel.High,
                                remediation = "Do not name your specific city or location details in bios."
                            )
                        )
                    }
                }
            }

            // D. Direct Organization hits
            identity.organizations.forEach { org ->
                val trimmedOrg = org.trim()
                if (trimmedOrg.isNotBlank() && !findings.any { it.type == FindingType.Organization && it.value.contains(trimmedOrg, ignoreCase = true) }) {
                    val orgRegex = Regex("\\b${Regex.escape(trimmedOrg)}\\b", RegexOption.IGNORE_CASE)
                    orgRegex.findAll(text).forEach { match ->
                        findings.add(
                            Finding(
                                type = FindingType.Organization,
                                value = trimmedOrg,
                                sourceUrl = sourceUrl,
                                evidenceSnippet = getSnippet(text, match.range),
                                confidence = 0.85f,
                                risk = RiskLevel.High,
                                remediation = "Dissociate company and school names from your public bios."
                            )
                        )
                    }
                }
            }
        }

        return findings.distinctBy { it.type.name + it.value + it.sourceUrl }
    }

    private fun getSnippet(text: String, range: IntRange): String {
        val start = (range.first - 30).coerceAtLeast(0)
        val end = (range.last + 30).coerceAtMost(text.length)
        return text.substring(start, end).replace("\n", " ").trim()
    }

    private val KNOWN_ORGS = setOf(
        "Replit", "Accenture", "Quandl", "Google", "Microsoft", "Facebook", "Meta", "Amazon", "Apple",
        "Netflix", "Uber", "Lyft", "GitHub", "GitLab", "Twitter", "Azul", "Stanford", "MIT", "Harvard",
        "Web Summit", "AI WEEK", "MCP Night", "Azul Systems", "Acme", "Acme Corp", "Stripe", "Spotify",
        "Shopify", "Airbnb", "Tesla", "SpaceX", "Adobe", "Salesforce", "Oracle", "IBM", "Intel", "NVIDIA",
        "AMD", "Qualcomm", "Cisco", "HP", "Dell", "Sony", "Samsung", "LG", "Flipkart", "Paytm", "Zomato",
        "Swiggy", "Ola", "Razorpay", "TCS", "Infosys", "Wipro", "HCL", "Cognizant", "Tech Mahindra",
        "Capgemini", "Deloitte", "PwC", "EY", "KPMG", "McKinsey", "BCG", "Bain", "Goldman Sachs",
        "Morgan Stanley", "JPMorgan", "Chase", "Citi", "HSBC", "Barclays", "Plaid", "Brex", "Scale AI",
        "OpenAI", "Anthropic", "Hugging Face", "Vercel", "Netlify", "Supabase", "Firebase", "MongoDB",
        "PostgreSQL", "MySQL", "JetBrains", "Android", "Slack", "Zoom", "Discord", "Telegram", "Signal",
        "WhatsApp", "Tiktok", "ByteDance", "Snapchat", "Pinterest", "LinkedIn", "Bitbucket", "Stack Overflow",
        "Medium", "Dev.to", "Hashnode", "Reddit", "Quora", "Product Hunt", "Y Combinator", "Techstars",
        "Berkeley", "Caltech", "Carnegie Mellon", "CMU", "Oxford", "Cambridge", "IIT", "IIT Delhi",
        "IIT Bombay", "IIT Madras", "IIT Kharagpur", "IIT Kanpur", "IIT Roorkee", "IIT Guwahati",
        "BITS Pilani", "IIIT", "IIIT Hyderabad", "IIIT Bangalore", "Delhi University", "DU", "NSUT",
        "DTU", "PEC", "VIT", "SRM", "Manipal"
    )

    private val KNOWN_LOCATIONS = setOf(
        "India", "Delhi", "New Delhi", "Gurgaon", "Gurugram", "Noida", "Bangalore", "Bengaluru", "Mumbai",
        "Pune", "Hyderabad", "Chennai", "Kolkata", "San Francisco", "California", "New York", "London",
        "UK", "USA", "Canada", "Germany", "Berlin", "Paris", "France", "Tokyo", "Japan", "Singapore",
        "Sydney", "Australia", "Ahmedabad", "Surat", "Jaipur", "Lucknow", "Kanpur", "Nagpur", "Indore",
        "Thane", "Bhopal", "Visakhapatnam", "Patna", "Vadodara", "Ghaziabad", "Ludhiana", "Agra", "Nashik",
        "Faridabad", "Meerut", "Rajkot", "Kalyan-Dombivli", "Vasai-Virar", "Varanasi", "Srinagar",
        "Aurangabad", "Dhanbad", "Amritsar", "Navi Mumbai", "Allahabad", "Ranchi", "Howrah", "Coimbatore",
        "Jabalpur", "Gwalior", "Vijayawada", "Jodhpur", "Madurai", "Raipur", "Kota", "Guwahati", "Chandigarh",
        "Solapur", "Hubli-Dharwad", "Bareilly", "Moradabad", "Mysore", "Haryana", "Karnataka", "Maharashtra",
        "Tamil Nadu", "Telangana", "Uttar Pradesh", "West Bengal", "Gujarat", "Rajasthan", "Punjab",
        "Kerala", "Bihar", "Madhya Pradesh", "Andhra Pradesh", "Assam", "Odisha", "Amsterdam", "Seattle",
        "Chicago", "Boston", "Austin", "Silicon Valley", "Dublin", "Zurich", "Geneva", "Munich", "Seoul",
        "Beijing", "Shanghai", "Hong Kong"
    )

    private fun determineTypeForLocationMatch(value: String, fullMatch: String = "", identity: IdentityInput? = null): FindingType {
        val lowerValue = value.lowercase()
        val lowerFull = fullMatch.lowercase()

        // 1. Direct match with user self-declared details
        identity?.organizations?.forEach { if (it.isNotBlank() && (it.equals(value, ignoreCase = true) || lowerValue.contains(it.lowercase()))) return FindingType.Organization }
        identity?.locations?.forEach { if (it.isNotBlank() && (it.equals(value, ignoreCase = true) || lowerValue.contains(it.lowercase()))) return FindingType.Location }

        // 2. Platform/Global Registry matches
        if (KNOWN_ORGS.any { it.equals(value, ignoreCase = true) }) return FindingType.Organization
        if (KNOWN_LOCATIONS.any { it.equals(value, ignoreCase = true) || lowerValue.contains(it.lowercase()) }) return FindingType.Location

        if (hasOrgSuffix(lowerValue)) return FindingType.Organization
        if (hasLocationSuffix(lowerValue)) return FindingType.Location

        // 3. Heuristics based on preposition/prefix
        if (lowerFull.startsWith("lives in") || lowerFull.startsWith("based in") || lowerFull.startsWith("located in")) {
            return FindingType.Location
        }

        // If it looks like a common country/state/city name structure (capitalized words usually associated with location)
        val locationClues = listOf("united states", "usa", "uk", "united kingdom", "germany", "france", "india", "canada", "australia", "switzerland", "netherlands", "singapore", "california", "new york", "london", "paris", "berlin", "tokyo", "delhi", "mumbai")
        if (locationClues.any { lowerValue.contains(it) }) {
            return FindingType.Location
        }

        // Default to Location for "from" prefix if we couldn't prove it's an org
        return FindingType.Location
    }

    private fun determineTypeForOrgMatch(value: String, fullMatch: String = "", identity: IdentityInput? = null): FindingType {
        val lowerValue = value.lowercase()
        val lowerFull = fullMatch.lowercase()

        // 1. Direct match with user self-declared details
        identity?.organizations?.forEach { if (it.isNotBlank() && (it.equals(value, ignoreCase = true) || lowerValue.contains(it.lowercase()))) return FindingType.Organization }
        identity?.locations?.forEach { if (it.isNotBlank() && (it.equals(value, ignoreCase = true) || lowerValue.contains(it.lowercase()))) return FindingType.Location }

        // 2. Platform/Global Registry matches
        if (KNOWN_ORGS.any { it.equals(value, ignoreCase = true) }) return FindingType.Organization
        if (KNOWN_LOCATIONS.any { it.equals(value, ignoreCase = true) || lowerValue.contains(it.lowercase()) }) return FindingType.Location

        if (hasOrgSuffix(lowerValue)) return FindingType.Organization
        if (hasLocationSuffix(lowerValue)) return FindingType.Location

        // 3. Heuristics based on preposition/prefix
        if (lowerFull.startsWith("works at") || lowerFull.startsWith("studied at") || 
            lowerFull.startsWith("employed at") || lowerFull.startsWith("member of") ||
            lowerFull.contains("developer at") || lowerFull.contains("engineer at") ||
            lowerFull.contains("student at") || lowerFull.contains("designer at") ||
            lowerFull.contains("manager at")) {
            return FindingType.Organization
        }

        // Default to Organization for professional context
        return FindingType.Organization
    }

    private fun hasOrgSuffix(value: String): Boolean {
        val orgSuffixes = listOf(
            "university", "college", "school", "inc", "corp", "ltd", "llc", "systems", 
            "technologies", "labs", "security", "foundation", "institute", "co", "group", 
            "capital", "partners", "ventures", "fair", "forum", "summit", "association",
            "corporation", "industries", "solutions", "software", "networks", "media",
            "digital", "analytics", "consulting", "global", "holding", "holdings"
        )
        return orgSuffixes.any { value.endsWith(" $it") || value == it || value.contains(" $it ") }
    }

    private fun hasLocationSuffix(value: String): Boolean {
        val locSuffixes = listOf(
            "city", "state", "country", "province", "county", "district", "valley", 
            "area", "region", "town", "village", "island", "lake", "beach", "mountain", 
            "mountains", "park", "road", "street", "avenue", "square", "plaza"
        )
        return locSuffixes.any { value.endsWith(" $it") || value == it || value.contains(" $it ") }
    }
}
