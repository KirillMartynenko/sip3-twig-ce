/*
 * Copyright 2018-2020 SIP3.IO, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sip3.twig.ce.controller

import io.sip3.twig.ce.MockitoExtension.any
import io.sip3.twig.ce.configuration.SecurityConfigurationProperties
import io.sip3.twig.ce.domain.Host
import io.sip3.twig.ce.service.host.HostService
import org.hamcrest.Matchers.`is`
import org.hamcrest.collection.IsCollectionWithSize
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.only
import org.mockito.BDDMockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.junit4.SpringRunner
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultHandlers.print
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@RunWith(SpringRunner::class)
@WebMvcTest(HostController::class, SecurityConfigurationProperties::class)
class HostControllerTest {

    companion object {

        val HOST_1 = Host("id1", "host1", listOf("10.10.10.0:5060", "10.10.10.0/28"), listOf("10.0.0.1"))
        val HOST_2 = Host("id2", "host2", listOf("10.10.20.0:5060", "10.10.20.0/28"), listOf("10.0.0.2"))
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var hostService: HostService

    @Test
    fun `Get all hosts`() {
        given(hostService.list()).willReturn(setOf(HOST_1, HOST_2))
        mockMvc.perform(get("/hosts"))
                .andDo(print())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", IsCollectionWithSize.hasSize<Any>(2)))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].name", `is`(HOST_1.name)))
                .andExpect(jsonPath("$[0].sip", `is`(HOST_1.sip)))
                .andExpect(jsonPath("$[0].media", `is`(HOST_1.media)))

    }

    @Test
    fun `Get host by name`() {
        given(hostService.getByName("host1")).willReturn(HOST_1)

        mockMvc.perform(get("/hosts/host1"))
                .andDo(print())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name", `is`(HOST_1.name)))
    }

    @Test
    fun `Get host not persisted yet by name`() {
        given(hostService.getByName("host3")).willThrow(EmptyResultDataAccessException(1))

        mockMvc.perform(get("/hosts/host3"))
                    .andExpect(status().isNotFound())

    }

    @Test
    fun `Add valid host`() {
        given(hostService.create(any())).willReturn(HOST_2)

        mockMvc.perform(post("/hosts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "host2", "sip": ["10.10.10.0:5060"], "media": ["10.10.10.0/28", "10.0.0.1"]}"""))
                .andExpect(status().isOk())
    }

    @Test
    fun `Add host with bad media address`() {
        mockMvc.perform(post("/hosts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "host2", "sip": ["10.10.10.0:5060"], "media": ["10.10.10.0/280", "10.0.0.1"]}"""))
                .andExpect(status().isBadRequest)
    }

    @Test
    fun `Add host already persisted`() {
        given(hostService.create(any())).willThrow(DuplicateKeyException(""))

        mockMvc.perform(post("/hosts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "host2", "sip": ["10.10.10.0:5060"], "media": ["10.10.10.0/28", "10.0.0.1"]}"""))
                .andExpect(status().isConflict)
    }

    @Test
    fun `Update valid host`() {
        given(hostService.create(any())).willReturn(HOST_2)

        mockMvc.perform(put("/hosts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "host2", "sip": ["10.10.10.0:5060"], "media": ["10.10.10.0/28", "10.0.0.1"]}"""))
                .andExpect(status().isOk)
    }

    @Test
    fun `Update host with bad media address`() {
        mockMvc.perform(put("/hosts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "host2", "sip": ["10.10.10.0:5060"], "media": ["10.10.10.0/280", "10.0.0.1"]}"""))
                .andExpect(status().isBadRequest)
    }

    @Test
    fun `Update host not persisted yet`() {
        given(hostService.update(any())).willThrow(EmptyResultDataAccessException(1))

        mockMvc.perform(put("/host/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "host2", "sip": ["10.10.10.0:5060"], "media": ["10.10.10.0/28", "10.0.0.1"]}"""))
                .andExpect(status().isNotFound)

    }

    @Test
    fun `Delete persisted host by name`() {
        this.mockMvc.perform(delete("/hosts/host2"))
                .andExpect(status().isOk)

        verify(hostService, only()).deleteByName(any())
    }

    @Test
    fun `Delete host by name not persisted yet`() {
        given(hostService.deleteByName(any())).willThrow(EmptyResultDataAccessException(1))

        this.mockMvc.perform(delete("/hosts/host2"))
                .andExpect(status().isNotFound)
    }

    @Test
    fun `Upload JSON file`() {
        HostControllerTest::class.java.getResourceAsStream("/correctHostList.json").use { fileStream ->
            val fileMock = MockMultipartFile("file", "hosts.json", null, fileStream)
            mockMvc.perform(multipart("/hosts/import")
                    .file(fileMock)).andExpect(status().isOk)
        }

        verify(hostService, only()).saveAll(any())
    }

    @Test
    fun `Upload JSON file with invalid host`() {
        HostControllerTest::class.java.getResourceAsStream("/incorrectAddressHostList.json").use { fileStream ->
            val fileMock = MockMultipartFile("file", "hosts.json", null, fileStream)

            mockMvc.perform(multipart("/hosts/import")
                    .file(fileMock))
                    .andExpect(status().isBadRequest)

        }
    }

    @Test
    fun `Upload JSON file with duplicate host`() {
        HostControllerTest::class.java.getResourceAsStream("/duplicatedHostList.json").use { fileStream ->
            val fileMock = MockMultipartFile("file", "hosts.json", null, fileStream)
            mockMvc.perform(multipart("/hosts/import")
                    .file(fileMock))
                    .andExpect(status().isBadRequest)
        }
    }
}

