/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.management.rest.resource.auth;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.JWTVerifyException;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.util.EnvironmentUtils;
import io.gravitee.management.model.*;
import io.gravitee.management.rest.model.TokenEntity;
import io.gravitee.management.rest.resource.AbstractResourceTest;
import io.gravitee.management.service.MembershipService;
import io.gravitee.management.service.exceptions.UserNotFoundException;
import io.gravitee.repository.management.model.MembershipDefaultReferenceId;
import io.gravitee.repository.management.model.MembershipReferenceType;
import io.gravitee.repository.management.model.RoleScope;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static javax.ws.rs.client.Entity.json;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.refEq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * @author Christophe LANNOY (chrislannoy.java at gmail.com)
 */
public class OAuth2AuthenticationResourceTest extends AbstractResourceTest {

    private final static String USER_SOURCE_OAUTH2 = "oauth2";

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort());

    protected String contextPath() {
        return "auth/oauth2";
    }

    @Before
    public void init() {
        cleanEnvironment();
        cleanRolesGroupMapping();
        reset(userService, groupService, roleService, membershipService);
    }

    private void cleanEnvironment() {
        getConfiguration().remove(EnvironmentUtils.encodedKey("tokenEndpoint"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("accessTokenProperty"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("userInfoEndpoint"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("authorizationHeader"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("mapping.email"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("mapping.id"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("mapping.lastname"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("mapping.firstname"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("mapping.picture"));
    }

    private void cleanRolesGroupMapping() {
        getConfiguration().remove(EnvironmentUtils.encodedKey("groups[0].mapping.condition"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("groups[0].mapping.values[0]"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("groups[0].mapping.values[1]"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("groups[1].mapping.condition"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("groups[1].mapping.values[0]"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("groups[2].mapping.condition"));
        getConfiguration().remove(EnvironmentUtils.encodedKey("groups[2].mapping.values[0]"));
    }

    private Map<String,Object> getConfiguration() {
        return this.authenticationProviderManager.getIdentityProviders().get(0).configuration();
    }

    @Test
    public void shouldConnectExistingUser() throws Exception {

        // -- MOCK
        //mock environment
        mockDefaultEnvironment();

        //mock oauth2 exchange authorisation code for access token
        mockExchangeAuthorizationCodeForAccessToken();

        //mock oauth2 user info call
        mockUserInfo(okJson(IOUtils.toString(read("/oauth2/json/user_info_response_body.json"), Charset.defaultCharset())));

        //mock DB find user by name
        UserEntity userEntity = mockUserEntity();
        userEntity.setId("janedoe@example.com");
        userEntity.setSource(USER_SOURCE_OAUTH2);
        userEntity.setSourceId("janedoe@example.com");
        userEntity.setPicture("http://example.com/janedoe/me.jpg");

        when(userService.findBySource(userEntity.getSource(),userEntity.getSourceId(), false)).thenReturn(userEntity);

        //mock DB update user picture , firstname ,lastname
        UpdateUserEntity user = new UpdateUserEntity();
        user.setFirstname("Jane");
        user.setLastname("Doe");
        user.setPicture("http://example.com/janedoe/me.jpg");

        when(userService.update(userEntity.getId(), refEq(user))).thenReturn(userEntity);

        //mock DB user connect
        when(userService.connect(userEntity.getId())).thenReturn(userEntity);


        // -- CALL

        AbstractAuthenticationResource.Payload payload = createPayload("the_client_id","http://localhost/callback","CoDe","StAtE");;

        Response response = target().request().post(json(payload));

        // -- VERIFY
        verify(userService, times(1)).findBySource(userEntity.getSource(),userEntity.getSourceId(), false);
        verify(userService, times(0)).create(any());
        verify(userService, times(1)).update(userEntity.getSourceId(), refEq(user));
        verify(userService, times(1)).connect(userEntity.getSourceId());

        assertEquals(HttpStatusCode.OK_200, response.getStatus());

        // verify response body
//        verifyUserInResponseBody(response);


        // verify jwt token
        verifyJwtToken(response);
    }

    private void verifyJwtToken(Response response) throws NoSuchAlgorithmException, InvalidKeyException, IOException, SignatureException, JWTVerifyException {
        TokenEntity responseToken = response.readEntity(TokenEntity.class);
        assertEquals("BEARER", responseToken.getType().name());

        String jwt = responseToken.getToken();

        JWTVerifier jwtVerifier = new JWTVerifier("myJWT4Gr4v1t33_S3cr3t");

        Map<String, Object> mapJwt = jwtVerifier.verify(jwt);

        assertEquals(mapJwt.get("sub"),"janedoe@example.com");

        assertEquals(mapJwt.get("firstname"),"Jane");
        assertEquals(mapJwt.get("iss"),"gravitee-management-auth");
        assertEquals(mapJwt.get("sub"),"janedoe@example.com");
        assertEquals(mapJwt.get("email"),"janedoe@example.com");
        assertEquals(mapJwt.get("lastname"),"Doe");
    }

    private void verifyJwtTokenIsNotPresent(Response response) throws NoSuchAlgorithmException, InvalidKeyException, IOException, SignatureException, JWTVerifyException {
        assertNull(response.getCookies().get(HttpHeaders.AUTHORIZATION));
    }

    private AbstractAuthenticationResource.Payload createPayload(String clientId, String redirectUri, String code, String state) {

        AbstractAuthenticationResource.Payload payload = new AbstractAuthenticationResource.Payload();
        payload.clientId = clientId;
        payload.redirectUri = redirectUri;
        payload.code = code;
        payload.state = state;

        return payload;
    }

    @Test
    public void shouldConnectNewUser() throws Exception {

        // -- MOCK
        //mock environment
        mockDefaultEnvironment();

        //mock oauth2 exchange authorisation code for access token
        mockExchangeAuthorizationCodeForAccessToken();

        //mock oauth2 user info call
        mockUserInfo(okJson(IOUtils.toString(read("/oauth2/json/user_info_response_body.json"), Charset.defaultCharset())));

        //mock DB find user by name
        when(userService.findBySource(USER_SOURCE_OAUTH2, "janedoe@example.com",false)).thenThrow(new UserNotFoundException("janedoe@example.com"));

        //mock create user
        NewExternalUserEntity newExternalUserEntity = mockNewExternalUserEntity();
        UserEntity createdUser = mockUserEntity();
        mockUserCreation(newExternalUserEntity, createdUser, true);

        //mock DB update user picture
        UpdateUserEntity user = mockUpdateUserPicture(createdUser);

        //mock DB user connect
        when(userService.connect("janedoe@example.com")).thenReturn(createdUser);


        // -- CALL

        AbstractAuthenticationResource.Payload payload = createPayload("the_client_id","http://localhost/callback","CoDe","StAtE");;

        Response response = target().request().post(json(payload));

        // -- VERIFY
        verify(userService, times(1)).findBySource(USER_SOURCE_OAUTH2, "janedoe@example.com",false);
        verify(userService, times(1)).create(refEq(newExternalUserEntity),eq(true));

        verify(userService, times(1)).update("janedoe@example.com", refEq(user));
        verify(userService, times(1)).connect("janedoe@example.com");

        assertEquals(HttpStatusCode.OK_200, response.getStatus());

        // verify response body
//        verifyUserInResponseBody(response);

        // verify jwt token
        verifyJwtToken(response);

    }

    /*
    private void verifyUserInResponseBody(Response response) {
        UserEntity responseUser = response.readEntity(UserEntity.class);

        assertEquals(responseUser.getEmail(),"janedoe@example.com");
        assertEquals(responseUser.getFirstname(),"Jane");
        assertEquals(responseUser.getLastname(),"Doe");
        assertEquals(responseUser.getUsername(),"janedoe@example.com");
        assertEquals(responseUser.getPicture(),"http://example.com/janedoe/me.jpg");
        assertEquals(responseUser.getSource(),"oauth2");
    }
    */

    private UpdateUserEntity mockUpdateUserPicture(UserEntity user) {
        UpdateUserEntity updateUserEntity = new UpdateUserEntity();
        updateUserEntity.setPicture("http://example.com/janedoe/me.jpg");
        updateUserEntity.setFirstname("Jane");
        updateUserEntity.setLastname("Doe");

        user.setPicture("http://example.com/janedoe/me.jpg");
        //user.setFirstname("Jane");
        //user.setLastname("Doe");

        when(userService.update(user.getId(), refEq(updateUserEntity))).thenReturn(user);
        return updateUserEntity;
    }

    private void mockUserCreation(NewExternalUserEntity newExternalUserEntity, UserEntity createdUser, boolean addDefaultRole) {
        when(userService.create(refEq(newExternalUserEntity), eq(addDefaultRole))).thenReturn(createdUser);
    }

    private UserEntity mockUserEntity() {
        UserEntity createdUser = new UserEntity();
        createdUser.setId("janedoe@example.com");
        createdUser.setSource(USER_SOURCE_OAUTH2);
        createdUser.setSourceId("248289761001");
        createdUser.setLastname("Doe");
        createdUser.setFirstname("Jane");
        createdUser.setEmail("janedoe@example.com");
        createdUser.setPicture("http://example.com/janedoe/me.jpg");
        return createdUser;
    }

    private NewExternalUserEntity mockNewExternalUserEntity() {
        NewExternalUserEntity newExternalUserEntity = new NewExternalUserEntity();
        newExternalUserEntity.setSource(USER_SOURCE_OAUTH2);
        newExternalUserEntity.setSourceId("248289761001");
        newExternalUserEntity.setLastname("Doe");
        newExternalUserEntity.setFirstname("Jane");
        newExternalUserEntity.setEmail("janedoe@example.com");
        newExternalUserEntity.setPicture("http://example.com/janedoe/me.jpg");
        return newExternalUserEntity;
    }

    @Test
    public void shouldNotConnectUserOn401UserInfo() throws Exception {

        // -- MOCK
        //mock environment
        mockDefaultEnvironment();

        //mock oauth2 exchange authorisation code for access token
        mockExchangeAuthorizationCodeForAccessToken();

        //mock oauth2 user info call
        mockUserInfo(WireMock.unauthorized().withBody(IOUtils.toString(read("/oauth2/json/user_info_401_response_body.json"), Charset.defaultCharset())));

        // -- CALL

        AbstractAuthenticationResource.Payload payload = createPayload("the_client_id","http://localhost/callback","CoDe","StAtE");;

        Response response = target().request().post(json(payload));

        // -- VERIFY
        verify(userService, times(0)).findBySource(USER_SOURCE_OAUTH2, anyString(), anyBoolean());
        verify(userService, times(0)).create(any());
        verify(userService, times(0)).update(anyString(), any());
        verify(userService, times(0)).connect(anyString());

        assertEquals(HttpStatusCode.UNAUTHORIZED_401, response.getStatus());

        // verify jwt token not present

        assertFalse(response.getCookies().containsKey(HttpHeaders.AUTHORIZATION));

    }


    @Test
    public void shouldNotConnectUserWhenMissingMailInUserInfo() throws Exception {

        // -- MOCK
        //mock environment
        mockWrongEnvironment();

        //mock oauth2 exchange authorisation code for access token
        mockExchangeAuthorizationCodeForAccessToken();

        //mock oauth2 user info call
        mockUserInfo(okJson(IOUtils.toString(read("/oauth2/json/user_info_response_body.json"), Charset.defaultCharset())));

        // -- CALL

        AbstractAuthenticationResource.Payload payload = createPayload("the_client_id","http://localhost/callback","CoDe","StAtE");;

        Response response = target().request().post(json(payload));

        // -- VERIFY
        verify(userService, times(0)).findBySource(USER_SOURCE_OAUTH2, anyString(), anyBoolean());
        verify(userService, times(0)).create(any());
        verify(userService, times(0)).update(anyString(), any());
        verify(userService, times(0)).connect(anyString());

        assertEquals(HttpStatusCode.BAD_REQUEST_400, response.getStatus());

        // verify jwt token not present
        assertFalse(response.getCookies().containsKey(HttpHeaders.AUTHORIZATION));
    }

    private void mockUserInfo(ResponseDefinitionBuilder responseDefinitionBuilder) throws IOException {
        stubFor(
                get("/userinfo")
                        .withHeader(HttpHeaders.ACCEPT, equalTo(MediaType.APPLICATION_JSON_TYPE.toString()))
                        .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer 2YotnFZFEjr1zCsicMWpAA"))
                        .willReturn(responseDefinitionBuilder));
    }

    private void mockExchangeAuthorizationCodeForAccessToken() throws IOException {
        String tokenRequestBody = ""
                + "code=CoDe&"
                + "grant_type=authorization_code&"
                + "redirect_uri=http%3A%2F%2Flocalhost%2Fcallback&"
                + "client_secret=the_client_secret&"
                + "client_id=the_client_id";

        stubFor(
                post("/token")
                        .withHeader(HttpHeaders.ACCEPT, equalTo(MediaType.APPLICATION_JSON_TYPE.toString()))
                        .withRequestBody(equalTo(tokenRequestBody))
                    .willReturn(okJson(IOUtils.toString(read("/oauth2/json/token_response_body.json"), Charset.defaultCharset()))));
    }

    private void mockDefaultEnvironment() {
        getConfiguration().put(EnvironmentUtils.encodedKey("tokenEndpoint"), "http://localhost:" + wireMockRule.port() + "/token");
        getConfiguration().put(EnvironmentUtils.encodedKey("userInfoEndpoint"), "http://localhost:" + wireMockRule.port() + "/userinfo");
        getConfiguration().put(EnvironmentUtils.encodedKey("accessTokenProperty"), "access_token");
        getConfiguration().put(EnvironmentUtils.encodedKey("authorizationHeader"), "Bearer %s");
        getConfiguration().put(EnvironmentUtils.encodedKey("mapping.email"), "email");
        getConfiguration().put(EnvironmentUtils.encodedKey("mapping.id"), "sub");
        getConfiguration().put(EnvironmentUtils.encodedKey("mapping.lastname"), "family_name");
        getConfiguration().put(EnvironmentUtils.encodedKey("mapping.firstname"), "given_name");
        getConfiguration().put(EnvironmentUtils.encodedKey("mapping.picture"), "picture");
    }

    private void mockWrongEnvironment() {
        mockDefaultEnvironment();
        getConfiguration().put(EnvironmentUtils.encodedKey("mapping.email"), "theEmail");
    }

    private InputStream read(String resource) throws IOException {
        return this.getClass().getResourceAsStream(resource);
    }

    @Test
    public void shouldConnectNewUserWithGroupsMappingFromUserInfo() throws Exception {

        // -- MOCK
        //mock environment
        mockDefaultEnvironment();
        mockGroupsMapping();
        mockRolesMapping();

        //mock oauth2 exchange authorisation code for access token
        mockExchangeAuthorizationCodeForAccessToken();

        //mock oauth2 user info call
        mockUserInfo(okJson(IOUtils.toString(read("/oauth2/json/user_info_response_body.json"), Charset.defaultCharset())));

        //mock DB find user by name
        when(userService.findBySource(USER_SOURCE_OAUTH2, "janedoe@example.com",false)).thenThrow(new UserNotFoundException("janedoe@example.com"));

        //mock create user
        NewExternalUserEntity newExternalUserEntity = mockNewExternalUserEntity();
        UserEntity createdUser = mockUserEntity();
        mockUserCreation(newExternalUserEntity, createdUser, true);

        //mock group search and association
        when(groupService.findByName("Example group")).thenReturn(Collections.singletonList(mockGroupEntity("group_id_1","Example group")));
        when(groupService.findByName("soft user")).thenReturn(Collections.singletonList(mockGroupEntity("group_id_2","soft user")));
        when(groupService.findByName("Others")).thenReturn(Collections.singletonList(mockGroupEntity("group_id_3","Others")));
        when(groupService.findByName("Api consumer")).thenReturn(Collections.singletonList(mockGroupEntity("group_id_4","Api consumer")));

        // mock role search
        when(roleService.findById(RoleScope.PORTAL, "USER")).thenReturn(mockRoleEntity(io.gravitee.management.model.permissions.RoleScope.PORTAL,"USER"));
        when(roleService.findById(RoleScope.MANAGEMENT, "ADMIN")).thenReturn(mockRoleEntity(io.gravitee.management.model.permissions.RoleScope.MANAGEMENT,"ADMIN"));

        RoleEntity roleApiUser = mockRoleEntity(io.gravitee.management.model.permissions.RoleScope.API,"USER");
        RoleEntity roleApplicationAdmin = mockRoleEntity(io.gravitee.management.model.permissions.RoleScope.APPLICATION,"ADMIN");

        when(roleService.findDefaultRoleByScopes(RoleScope.API,RoleScope.APPLICATION)).thenReturn(Arrays.asList(roleApiUser,roleApplicationAdmin));

        when(membershipService.addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_1"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.API, "USER"))).thenReturn(mockMemberEntity());

        when(membershipService.addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_2"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.API, "USER"))).thenReturn(mockMemberEntity());

        when(membershipService.addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_2"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.APPLICATION, "ADMIN"))).thenReturn(mockMemberEntity());

        when(membershipService.addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_4"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.API, "USER"))).thenReturn(mockMemberEntity());

        when(membershipService.addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_4"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.APPLICATION, "ADMIN"))).thenReturn(mockMemberEntity());

        when(membershipService.addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.MANAGEMENT, MembershipDefaultReferenceId.DEFAULT.name()),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.MANAGEMENT, "ADMIN"))).thenReturn(mockMemberEntity());

        when(membershipService.addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.PORTAL, MembershipDefaultReferenceId.DEFAULT.name()),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.PORTAL, "USER"))).thenReturn(mockMemberEntity());

        //mock DB update user picture
        UpdateUserEntity updateUserEntity = mockUpdateUserPicture(createdUser);

        //mock DB user connect
        when(userService.connect("janedoe@example.com")).thenReturn(createdUser);


        // -- CALL

        AbstractAuthenticationResource.Payload payload = createPayload("the_client_id","http://localhost/callback","CoDe","StAtE");

        Response response = target().request().post(json(payload));

        // -- VERIFY
        verify(userService, times(1)).findBySource(USER_SOURCE_OAUTH2, "janedoe@example.com",false);
        verify(userService, times(1)).create(refEq(newExternalUserEntity),eq(true));

        verify(userService, times(1)).update("janedoe@example.com", refEq(updateUserEntity));
        verify(userService, times(1)).connect("janedoe@example.com");

        //verify group creations
        verify(membershipService, times(1)).addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_1"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.API, "USER"));

        verify(membershipService, times(1)).addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_1"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.APPLICATION, "ADMIN"));

        verify(membershipService, times(1)).addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_2"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.API, "USER"));

        verify(membershipService, times(1)).addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_2"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.APPLICATION, "ADMIN"));

        verify(membershipService, times(0)).addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_3"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.API, "USER"));

        verify(membershipService, times(0)).addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_3"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.APPLICATION, "ADMIN"));

        verify(membershipService, times(1)).addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_4"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.API, "USER"));

        verify(membershipService, times(1)).addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.GROUP, "group_id_4"),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.APPLICATION, "ADMIN"));

        verify(membershipService, times(1)).addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.MANAGEMENT, MembershipDefaultReferenceId.DEFAULT.name()),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.MANAGEMENT, "ADMIN"));

        verify(membershipService, times(1)).addOrUpdateMember(
                new MembershipService.MembershipReference(MembershipReferenceType.PORTAL, MembershipDefaultReferenceId.DEFAULT.name()),
                new MembershipService.MembershipUser("janedoe@example.com", null),
                new MembershipService.MembershipRole(RoleScope.PORTAL, "USER"));

        assertEquals(HttpStatusCode.OK_200, response.getStatus());

        // verify response body
//        verifyUserInResponseBody(response);

        // verify jwt token
        verifyJwtToken(response);

    }

    @Test
    public void shouldConnectNewUserWithNoMatchingGroupsMappingFromUserInfo() throws Exception {

        // -- MOCK
        //mock environment
        mockDefaultEnvironment();
        mockGroupsMapping();

        //mock oauth2 exchange authorisation code for access token
        mockExchangeAuthorizationCodeForAccessToken();

        //mock oauth2 user info call
        mockUserInfo(okJson(IOUtils.toString(read("/oauth2/json/user_info_response_body_no_matching.json"), Charset.defaultCharset())));

        //mock DB find user by name
        when(userService.findBySource(USER_SOURCE_OAUTH2, "janedoe@example.com",false)).thenThrow(new UserNotFoundException("janedoe@example.com"));

        //mock create user
        NewExternalUserEntity newExternalUserEntity = mockNewExternalUserEntity();
        UserEntity createdUser = mockUserEntity();
        mockUserCreation(newExternalUserEntity, createdUser, true);

        //mock DB update user picture
        UpdateUserEntity updateUserEntity = mockUpdateUserPicture(createdUser);

        //mock DB user connect
        when(userService.connect("janedoe@example.com")).thenReturn(createdUser);


        // -- CALL

        AbstractAuthenticationResource.Payload payload = createPayload("the_client_id","http://localhost/callback","CoDe","StAtE");;

        Response response = target().request().post(json(payload));

        // -- VERIFY
        verify(userService, times(1)).findBySource(USER_SOURCE_OAUTH2, "janedoe@example.com",false);
        verify(userService, times(1)).create(refEq(newExternalUserEntity),eq(true));

        verify(userService, times(1)).update("janedoe@example.com", refEq(updateUserEntity));
        verify(userService, times(1)).connect("janedoe@example.com");

        //verify group creations
        verify(membershipService, times(0)).addOrUpdateMember(
                any(MembershipService.MembershipReference.class),
                any(MembershipService.MembershipUser.class),
                any(MembershipService.MembershipRole.class));

        assertEquals(HttpStatusCode.OK_200, response.getStatus());

        // verify response body
//        verifyUserInResponseBody(response);

        // verify jwt token
        verifyJwtToken(response);
    }

    @Test
    public void shouldNotConnectNewUserWithGroupsMappingFromUserInfoWhenGroupIsNotFound() throws Exception {

        // -- MOCK
        //mock environment
        mockDefaultEnvironment();
        mockGroupsMapping();

        //mock oauth2 exchange authorisation code for access token
        mockExchangeAuthorizationCodeForAccessToken();

        //mock oauth2 user info call
        mockUserInfo(okJson(IOUtils.toString(read("/oauth2/json/user_info_response_body.json"), Charset.defaultCharset())));

        //mock DB find user by name
        when(userService.findBySource(USER_SOURCE_OAUTH2, "janedoe@example.com",false)).thenThrow(new UserNotFoundException("janedoe@example.com"));

        //mock group search and association
        when(groupService.findByName("Example group")).thenReturn(Collections.emptyList());
        when(groupService.findByName("soft user")).thenReturn(Collections.emptyList());
        when(groupService.findByName("Others")).thenReturn(Collections.emptyList());
        when(groupService.findByName("Api consumer")).thenReturn(Collections.emptyList());

        NewExternalUserEntity newExternalUserEntity = mockNewExternalUserEntity();
        UserEntity createdUser = mockUserEntity();
        mockUserCreation(newExternalUserEntity, createdUser, true);

        // -- CALL

        AbstractAuthenticationResource.Payload payload = createPayload("the_client_id","http://localhost/callback","CoDe","StAtE");;

        Response response = target().request().post(json(payload));

        // -- VERIFY
        verify(userService, times(1)).findBySource(USER_SOURCE_OAUTH2, "janedoe@example.com",false);
        verify(userService, times(0)).create(any(NewExternalUserEntity.class),anyBoolean());

        verify(userService, times(0)).update(any(String.class), any(UpdateUserEntity.class));
        verify(userService, times(0)).connect(anyString());

        //verify group creations
        verify(membershipService, times(0)).addOrUpdateMember(
                any(MembershipService.MembershipReference.class),
                any(MembershipService.MembershipUser.class),
                any(MembershipService.MembershipRole.class));

        assertEquals(HttpStatusCode.INTERNAL_SERVER_ERROR_500, response.getStatus());

        // verify jwt token
        verifyJwtTokenIsNotPresent(response);
    }


    @Test
    public void shouldNotConnectNewUserWhenWrongELGroupsMapping() throws Exception {

        // -- MOCK
        //mock environment
        mockDefaultEnvironment();
        mockWrongELGroupsMapping();

        //mock oauth2 exchange authorisation code for access token
        mockExchangeAuthorizationCodeForAccessToken();

        //mock oauth2 user info call
        mockUserInfo(okJson(IOUtils.toString(read("/oauth2/json/user_info_response_body.json"), Charset.defaultCharset())));

        //mock DB find user by name
        when(userService.findBySource(USER_SOURCE_OAUTH2, "janedoe@example.com",false)).thenThrow(new UserNotFoundException("janedoe@example.com"));

        NewExternalUserEntity newExternalUserEntity = mockNewExternalUserEntity();
        UserEntity createdUser = mockUserEntity();
        mockUserCreation(newExternalUserEntity, createdUser, true);

        // -- CALL

        AbstractAuthenticationResource.Payload payload = createPayload("the_client_id","http://localhost/callback","CoDe","StAtE");;

        Response response = target().request().post(json(payload));

        // -- VERIFY
        verify(userService, times(1)).findBySource(USER_SOURCE_OAUTH2,"janedoe@example.com",false);
        verify(userService, times(0)).create(any(NewExternalUserEntity.class),anyBoolean());

        verify(userService, times(0)).update(any(String.class), any(UpdateUserEntity.class));
        verify(userService, times(0)).connect(anyString());

        //verify group creations
        verify(membershipService, times(0)).addOrUpdateMember(
                any(MembershipService.MembershipReference.class),
                any(MembershipService.MembershipUser.class),
                any(MembershipService.MembershipRole.class));

        assertEquals(HttpStatusCode.INTERNAL_SERVER_ERROR_500, response.getStatus());

        // verify jwt token
        verifyJwtTokenIsNotPresent(response);
    }

    private RoleEntity mockRoleEntity(io.gravitee.management.model.permissions.RoleScope scope, String name) {
        RoleEntity role = new RoleEntity();
        role.setScope(scope);
        role.setName(name);
        return role;
    }

    private GroupEntity mockGroupEntity( String id, String name) {
        GroupEntity groupEntity = new GroupEntity();
        groupEntity.setId(id);
        groupEntity.setName(name);
        return groupEntity;
    }

    private MemberEntity mockMemberEntity() {
        return mock(MemberEntity.class);
    }

    private void mockGroupsMapping() {

        getConfiguration().put(EnvironmentUtils.encodedKey("groups[0].mapping.condition"), "{#jsonPath(#profile, '$.identity_provider_id') == 'idp_5' && #jsonPath(#profile, '$.job_id') != 'API_BREAKER'}");
        getConfiguration().put(EnvironmentUtils.encodedKey("groups[0].mapping.values[0]"), "Example group");
        getConfiguration().put(EnvironmentUtils.encodedKey("groups[0].mapping.values[1]"), "soft user");

        getConfiguration().put(EnvironmentUtils.encodedKey("groups[1].mapping.condition"), "{#jsonPath(#profile, '$.identity_provider_id') == 'idp_6'}");
        getConfiguration().put(EnvironmentUtils.encodedKey("groups[1].mapping.values[0]"), "Others");

        getConfiguration().put(EnvironmentUtils.encodedKey("groups[2].mapping.condition"), "{#jsonPath(#profile, '$.job_id') != 'API_BREAKER'}");
        getConfiguration().put(EnvironmentUtils.encodedKey("groups[2].mapping.values[0]"), "Api consumer");
    }

    private void mockRolesMapping() {

        getConfiguration().put(EnvironmentUtils.encodedKey("roles[0].mapping.condition"), "{#jsonPath(#profile, '$.identity_provider_id') == 'idp_5' && #jsonPath(#profile, '$.job_id') != 'API_BREAKER'}");
        getConfiguration().put(EnvironmentUtils.encodedKey("roles[0].mapping.values[0]"), "PORTAL:USER");
        getConfiguration().put(EnvironmentUtils.encodedKey("roles[0].mapping.values[1]"), "MANAGEMENT:admin");

        getConfiguration().put(EnvironmentUtils.encodedKey("roles[1].mapping.condition"), "{#jsonPath(#profile, '$.identity_provider_id') == 'idp_6'}");
        getConfiguration().put(EnvironmentUtils.encodedKey("roles[1].mapping.values[0]"), "PORTAL:USER");

        getConfiguration().put(EnvironmentUtils.encodedKey("roles[2].mapping.condition"), "{#jsonPath(#profile, '$.job_id') != 'API_BREAKER'}");
        getConfiguration().put(EnvironmentUtils.encodedKey("roles[2].mapping.values[0]"), "PORTAL:USER");
    }

    private void mockWrongELGroupsMapping() {

        getConfiguration().put(EnvironmentUtils.encodedKey("groups[0].mapping.condition"), "Some Soup");
        getConfiguration().put(EnvironmentUtils.encodedKey("groups[0].mapping.values[0]"), "Example group");
        getConfiguration().put(EnvironmentUtils.encodedKey("groups[0].mapping.values[1]"), "soft user");

        getConfiguration().put(EnvironmentUtils.encodedKey("groups[1].mapping.condition"), "{#jsonPath(#profile, '$.identity_provider_id') == 'idp_6'}");
        getConfiguration().put(EnvironmentUtils.encodedKey("groups[1].mapping.values[0]"), "Others");

        getConfiguration().put(EnvironmentUtils.encodedKey("groups[2].mapping.condition"), "{#jsonPath(#profile, '$.job_id') != 'API_BREAKER'}");
        getConfiguration().put(EnvironmentUtils.encodedKey("groups[2].mapping.values[0]"), "Api consumer");
    }



}
