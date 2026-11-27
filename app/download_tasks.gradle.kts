/*
 * Copyright 2026 The MediaPipe Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

val assetDir = extra["ASSET_DIR"] as String

fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val faceLandmarkerUrl =
    "https://huggingface.co/litert-community/MediaPipe-FaceLandmarker/resolve/main/face_landmarker.task"
val faceLandmarkerFile = file("$assetDir/face_landmarker.task")
val faceLandmarkerSha256 = "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"

tasks.register("downloadFaceLandmarkerModel") {
    outputs.file(faceLandmarkerFile)
    onlyIf { !faceLandmarkerFile.exists() }
    doLast {
        faceLandmarkerFile.parentFile?.mkdirs()
        val temporaryFile = file("$assetDir/face_landmarker.task.tmp")
        try {
            URI(faceLandmarkerUrl).toURL().openStream().use { input ->
                Files.copy(input, temporaryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val actual = temporaryFile.sha256Hex()
            if (actual != faceLandmarkerSha256) {
                throw GradleException(
                    "Face Landmarker model checksum mismatch: expected $faceLandmarkerSha256, got $actual"
                )
            }
            Files.move(
                temporaryFile.toPath(),
                faceLandmarkerFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporaryFile.delete()
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadFaceLandmarkerModel")
}
