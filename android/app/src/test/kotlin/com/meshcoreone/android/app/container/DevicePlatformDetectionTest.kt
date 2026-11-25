// PortedFrom: MC1Services/Tests/MC1ServicesTests/Models/DevicePlatformTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.DevicePlatform
import kotlin.test.assertEquals
import org.junit.Test

/** Every source case runs against the merged `core:model` detector, with the source's exact model strings. */
class DevicePlatformDetectionTest {

    @OriginalCase("DevicePlatformTests::Heltec V2 detected as ESP32()")
    @Test fun case00HeltecV2DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Heltec V2")) }

    @OriginalCase("DevicePlatformTests::Heltec V3 detected as ESP32()")
    @Test fun case01HeltecV3DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Heltec V3")) }

    @OriginalCase("DevicePlatformTests::Heltec V4 detected as ESP32()")
    @Test fun case02HeltecV4DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Heltec V4")) }

    @OriginalCase("DevicePlatformTests::Heltec Tracker detected as ESP32()")
    @Test fun case03HeltecTrackerDetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Heltec Tracker")) }

    @OriginalCase("DevicePlatformTests::Heltec E290 detected as ESP32()")
    @Test fun case04HeltecE290DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Heltec E290")) }

    @OriginalCase("DevicePlatformTests::Heltec E213 detected as ESP32()")
    @Test fun case05HeltecE213DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Heltec E213")) }

    @OriginalCase("DevicePlatformTests::Heltec T190 detected as ESP32()")
    @Test fun case06HeltecT190DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Heltec T190")) }

    @OriginalCase("DevicePlatformTests::Heltec CT62 detected as ESP32()")
    @Test fun case07HeltecCt62DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Heltec CT62")) }

    @OriginalCase("DevicePlatformTests::T-Beam detected as ESP32()")
    @Test fun case08TBeamDetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("T-Beam")) }

    @OriginalCase("DevicePlatformTests::T-Deck detected as ESP32()")
    @Test fun case09TDeckDetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("T-Deck")) }

    @OriginalCase("DevicePlatformTests::T-LoRa detected as ESP32()")
    @Test fun case10TLoraDetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("T-LoRa")) }

    @OriginalCase("DevicePlatformTests::TLora detected as ESP32()")
    @Test fun case11TloraDetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("TLora")) }

    @OriginalCase("DevicePlatformTests::Xiao S3 WIO detected as ESP32()")
    @Test fun case12XiaoS3WioDetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Xiao S3 WIO")) }

    @OriginalCase("DevicePlatformTests::Xiao C3 detected as ESP32()")
    @Test fun case13XiaoC3DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Xiao C3")) }

    @OriginalCase("DevicePlatformTests::Xiao C6 detected as ESP32()")
    @Test fun case14XiaoC6DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Xiao C6")) }

    @OriginalCase("DevicePlatformTests::RAK 3112 detected as ESP32()")
    @Test fun case15Rak3112DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("RAK 3112")) }

    @OriginalCase("DevicePlatformTests::Station G2 detected as ESP32()")
    @Test fun case16StationG2DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Station G2")) }

    @OriginalCase("DevicePlatformTests::Meshadventurer detected as ESP32()")
    @Test fun case17MeshadventurerDetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Meshadventurer")) }

    @OriginalCase("DevicePlatformTests::Generic ESP32 detected as ESP32()")
    @Test fun case18GenericEsp32DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("Generic ESP32")) }

    @OriginalCase("DevicePlatformTests::ThinkNode M2 detected as ESP32()")
    @Test fun case19ThinknodeM2DetectedAsEsp32() { assertEquals(DevicePlatform.ESP32, DevicePlatform.detect("ThinkNode M2")) }

    @OriginalCase("DevicePlatformTests::MeshPocket detected as nRF52()")
    @Test fun case20MeshpocketDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("MeshPocket")) }

    @OriginalCase("DevicePlatformTests::Mesh Pocket (with space) detected as nRF52()")
    @Test fun case21MeshPocketWithSpaceDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Mesh Pocket")) }

    @OriginalCase("DevicePlatformTests::T114 detected as nRF52()")
    @Test fun case22T114DetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("T114")) }

    @OriginalCase("DevicePlatformTests::Mesh Solar detected as nRF52()")
    @Test fun case23MeshSolarDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Mesh Solar")) }

    @OriginalCase("DevicePlatformTests::Xiao-nrf52 detected as nRF52()")
    @Test fun case24XiaoNrf52DetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Xiao-nrf52")) }

    @OriginalCase("DevicePlatformTests::Xiao_nrf52 detected as nRF52()")
    @Test fun case25XiaoNrf52DetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Xiao_nrf52")) }

    @OriginalCase("DevicePlatformTests::WM1110 detected as nRF52()")
    @Test fun case26Wm1110DetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("WM1110")) }

    @OriginalCase("DevicePlatformTests::Wio Tracker detected as nRF52()")
    @Test fun case27WioTrackerDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Wio Tracker")) }

    @OriginalCase("DevicePlatformTests::T1000-E detected as nRF52()")
    @Test fun case28T1000EDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("T1000-E")) }

    @OriginalCase("DevicePlatformTests::SenseCap Solar detected as nRF52()")
    @Test fun case29SensecapSolarDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("SenseCap Solar")) }

    @OriginalCase("DevicePlatformTests::WisMesh Tag detected as nRF52()")
    @Test fun case30WismeshTagDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("WisMesh Tag")) }

    @OriginalCase("DevicePlatformTests::RAK 4631 detected as nRF52()")
    @Test fun case31Rak4631DetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("RAK 4631")) }

    @OriginalCase("DevicePlatformTests::RAK 3401 detected as nRF52()")
    @Test fun case32Rak3401DetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("RAK 3401")) }

    @OriginalCase("DevicePlatformTests::T-Echo detected as nRF52()")
    @Test fun case33TEchoDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("T-Echo")) }

    @OriginalCase("DevicePlatformTests::ThinkNode-M1 detected as nRF52()")
    @Test fun case34ThinknodeM1DetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("ThinkNode-M1")) }

    @OriginalCase("DevicePlatformTests::ThinkNode M3 detected as nRF52()")
    @Test fun case35ThinknodeM3DetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("ThinkNode M3")) }

    @OriginalCase("DevicePlatformTests::ThinkNode-M6 detected as nRF52()")
    @Test fun case36ThinknodeM6DetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("ThinkNode-M6")) }

    @OriginalCase("DevicePlatformTests::Ikoka detected as nRF52()")
    @Test fun case37IkokaDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Ikoka")) }

    @OriginalCase("DevicePlatformTests::ProMicro detected as nRF52()")
    @Test fun case38PromicroDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("ProMicro")) }

    @OriginalCase("DevicePlatformTests::Minewsemi detected as nRF52()")
    @Test fun case39MinewsemiDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Minewsemi")) }

    @OriginalCase("DevicePlatformTests::Meshtiny detected as nRF52()")
    @Test fun case40MeshtinyDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Meshtiny")) }

    @OriginalCase("DevicePlatformTests::Keepteen detected as nRF52()")
    @Test fun case41KeepteenDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Keepteen")) }

    @OriginalCase("DevicePlatformTests::Nano G2 Ultra detected as nRF52()")
    @Test fun case42NanoG2UltraDetectedAsNrf52() { assertEquals(DevicePlatform.NRF52, DevicePlatform.detect("Nano G2 Ultra")) }

    @OriginalCase("DevicePlatformTests::Bare 'Heltec' vendor name is unknown (not assumed ESP32)()")
    @Test fun case43BareHeltecVendorNameIsUnknownNotAssumedEsp32() { assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.detect("Heltec")) }

    @OriginalCase("DevicePlatformTests::Empty model string returns unknown()")
    @Test fun case44EmptyModelStringReturnsUnknown() { assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.detect("")) }

    @OriginalCase("DevicePlatformTests::Unrecognized device returns unknown()")
    @Test fun case45UnrecognizedDeviceReturnsUnknown() { assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.detect("SomeNewDevice XYZ")) }

    @OriginalCase("DevicePlatformTests::ESP32 pacing is 60ms()")
    @Test fun case46Esp32PacingIs60ms() { assertEquals(0.060, DevicePlatform.ESP32.recommendedWritePacingSeconds) }

    @OriginalCase("DevicePlatformTests::nRF52 pacing is 25ms()")
    @Test fun case47Nrf52PacingIs25ms() { assertEquals(0.025, DevicePlatform.NRF52.recommendedWritePacingSeconds) }

    @OriginalCase("DevicePlatformTests::Unknown pacing is 60ms (conservative default)()")
    @Test fun case48UnknownPacingIs60msConservativeDefault() { assertEquals(0.060, DevicePlatform.UNKNOWN.recommendedWritePacingSeconds) }

}
