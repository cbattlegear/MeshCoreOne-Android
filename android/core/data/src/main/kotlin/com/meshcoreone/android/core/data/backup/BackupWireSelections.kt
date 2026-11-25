// AndroidOnly: WP-203 Materialize only fields consumed by the pinned Codable DTOs.
package com.meshcoreone.android.core.data.backup

internal object BackupWireSelections {
    private val scalar = JsonReadSelection.Scalar
    private val strings = JsonReadSelection.Array(scalar)
    private fun fields(names: String, nested: Map<String, JsonReadSelection> = emptyMap()): JsonReadSelection.Object =
        JsonReadSelection.Object(names.split(Regex("\\s+")).filter(String::isNotEmpty).associateWith { scalar } + nested)

    private val connectionMethod = JsonReadSelection.Object(mapOf(
        "bluetooth" to fields("peripheralUUID displayName"),
        "wifi" to fields("host port displayName"),
    ), rejectUnknown = true)
    private val traceRun = fields("id date success roundTripMs", mapOf("hopsSNR" to JsonReadSelection.Array(scalar)))
    private val region = fields("countryCode administrativeAreaCode countyKey source")
    val records: Map<String, JsonReadSelection.Object> = mapOf(
        "devices" to fields(
            """id radioID publicKey nodeName firmwareVersion firmwareVersionString manufacturerName buildDate maxContacts maxChannels
            frequency bandwidth spreadingFactor codingRate txPower maxTxPower latitude longitude blePin clientRepeat pathHashMode
            defaultFloodScopeName preRepeatFrequency preRepeatBandwidth preRepeatSpreadingFactor preRepeatCodingRate manualAddContacts
            autoAddConfig autoAddMaxHops multiAcks telemetryModeBase telemetryModeLoc telemetryModeEnv advertLocationPolicy
            lastConnected lastContactSync isActive ocvPreset appliedRadioPresetID customOCVArrayString""",
            mapOf("connectionMethods" to JsonReadSelection.Array(connectionMethod), "knownRegions" to strings)),
        "contacts" to fields(
            """id radioID publicKey name typeRawValue flags outPathLength outPath lastAdvertTimestamp latitude longitude
            lastModified lastHeardTimestamp nickname isBlocked isMuted isFavorite isPinned lastMessageDate unreadCount unreadMentionCount
            ocvPreset customOCVArrayString avatarImageData"""),
        "channels" to fields(
            """id radioID index name secret isEnabled lastMessageDate unreadCount unreadMentionCount notificationLevel isFavorite isPinned
            floodScopeModeRawValue regionScope"""),
        "messages" to fields(
            """id radioID contactID channelIndex text timestamp createdAt sortDate direction status textType ackCode pathLength snr
            pathNodes senderKeyPrefix senderNodeName isRead replyToID roundTripTime heardRepeats sendCount retryAttempt maxRetryAttempts
            deduplicationKey linkPreviewURL linkPreviewTitle linkPreviewImageData linkPreviewIconData linkPreviewFetched
            containsSelfMention mentionSeen failureSeen timestampCorrected senderTimestamp reactionSummary routeType regionScope""",
            mapOf("regionScopeMatches" to strings)),
        "messageRepeats" to fields("id messageID receivedAt pathNodes pathLength snr rssi rxLogEntryID"),
        "reactions" to fields("id messageID emoji senderName messageHash rawText receivedAt channelIndex contactID radioID"),
        "remoteNodeSessions" to fields(
            """id radioID publicKey name role latitude longitude isConnected permissionLevel lastConnectedDate lastBatteryMillivolts
            lastUptimeSeconds lastNoiseFloor unreadCount notificationLevel isFavorite isPinned lastRxAirtimeSeconds neighborCount
            lastSyncTimestamp lastMessageDate"""),
        "roomMessages" to fields(
            """id sessionID authorKeyPrefix authorName text timestamp createdAt isFromSelf deduplicationKey statusRawValue
            ackCode roundTripTime retryAttempt maxRetryAttempts failureSeen"""),
        "savedTracePaths" to fields("id radioID name pathBytes hashSize createdDate", mapOf("runs" to JsonReadSelection.Array(traceRun))),
        "blockedChannelSenders" to fields("id name radioID dateBlocked"),
        "nodeStatusSnapshots" to fields(
            """id timestamp nodePublicKey selfRadioID batteryMillivolts lastSNR lastRSSI noiseFloor uptimeSeconds rxAirtimeSeconds
            packetsSent packetsReceived receiveErrors sentDirect sentFlood receivedDirect receivedFlood directDuplicates
            floodDuplicates postedCount postPushCount latitude longitude altitude""",
            mapOf(
                "neighborSnapshots" to JsonReadSelection.Array(fields("publicKeyPrefix snr secondsAgo")),
                "telemetryEntries" to JsonReadSelection.Array(fields("channel type value")),
            )),
        "discoveredNodes" to fields(
            """id radioID publicKey name typeRawValue lastHeard lastAdvertTimestamp latitude longitude outPathLength outPath
            inboundHopCount inboundHopAdvertTimestamp"""),
    )
    private val preferences = JsonReadSelection.Object(
        (BackupUserDefaults.boolMappings.map { it.key.rawValue } + BackupUserDefaults.stringMappings.map { it.key.rawValue })
            .associateWith { scalar } + mapOf(
            "autoDeleteStaleNodesDays" to scalar, "frequentEmojis" to strings, "recentEmojis" to strings, "regionSelection" to region,
        ))
    val envelope = JsonReadSelection.Object(
        records.mapValues { JsonReadSelection.Array(it.value) } + mapOf(
            "version" to scalar, "exportDate" to scalar, "appVersion" to scalar, "appBuild" to scalar,
            "manifest" to fields(BackupModelKind.entries.joinToString(" ") { it.countKey }), "userDefaults" to preferences,
        ))
}
