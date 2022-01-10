package be.cytomine.image.server

/*
* Copyright (c) 2009-2022. Authors: see NOTICE file.
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

import be.cytomine.command.*
import be.cytomine.security.SecUser
import be.cytomine.utils.ModelService
import be.cytomine.utils.Task
import grails.plugin.springsecurity.SpringSecurityUtils
import org.codehaus.groovy.grails.web.json.JSONObject

import static org.springframework.security.acls.domain.BasePermission.*

class StorageService extends ModelService {

    def cytomineService
    def transactionService
    def permissionService
    def securityACLService
    def springSecurityService
    def currentRoleServiceProxy

    static transactional = true

    def currentDomain() {
        return Storage
    }

    def list() {
        return securityACLService.getStorageList(cytomineService.currentUser, true)
    }

    def list(SecUser user) {
        return securityACLService.getStorageList(user, false)
    }

    def read(def id) {
        def storage =  Storage.read((Long) id)
        if(storage) {
            securityACLService.check(storage,READ)
        }
        storage
    }

    /**
     * Add the new domain with JSON data
     * @param json New domain data)
     * @return Response structure (created domain data,..)
     */
    def add(def json) {
        SecUser currentUser = cytomineService.getCurrentUser()
        securityACLService.checkUser(currentUser)
        json.user = (currentRoleServiceProxy.isAdminByNow(currentUser)) ? json.user : currentUser.id
        Command c = new AddCommand(user: currentUser)
        executeCommand(c,null,json)
    }

    def afterAdd(Storage domain, def response) {
        log.info("Add permission on $domain to ${domain.user.username}")
        if(!domain.hasACLPermission(READ)) {
            permissionService.addPermission(domain, domain.user.username, READ)
        }
        if(!domain.hasACLPermission(WRITE)) {
            permissionService.addPermission(domain, domain.user.username, WRITE)
        }
        if(!domain.hasACLPermission(ADMINISTRATION)) {
            permissionService.addPermission(domain, domain.user.username, ADMINISTRATION)
        }
    }

    /**
     * Update this domain with new data from json
     * @param domain Domain to update
     * @param jsonNewData New domain datas
     * @return  Response structure (new domain data, old domain data..)
     */
    def update(Storage storage,def jsonNewData) {
        securityACLService.check(storage, ADMINISTRATION)
        SecUser currentUser = cytomineService.getCurrentUser()
        Command c = new EditCommand(user: currentUser)
        executeCommand(c,storage,jsonNewData)
    }

    /**
     * Delete domain in argument
     * @param json JSON that was passed in request parameter
     * @param security Security service object (user for right check)
     * @return Response structure (created domain data,..)
     */
    def delete(Storage storage, Transaction transaction = null, Task task = null, boolean printMessage = true) {
        securityACLService.check(storage.container(), ADMINISTRATION)
        SecUser currentUser = cytomineService.getCurrentUser()
        Command c = new DeleteCommand(user: currentUser,transaction:transaction)
        return executeCommand(c,storage,null)
    }

    def deleteDependentUploadedFile(Storage storage, Transaction transaction, Task task = null) {
        // TODO: do we want to allow this ?
    }

    def getStringParamsI18n(def domain) {
        return [domain.id, domain.name]
    }

    def initUserStorage(SecUser user) {
        log.info ("create storage for $user.username")
        SpringSecurityUtils.doWithAuth(user.username, {
            Command c = new AddCommand(user: user)
            executeCommand(c,null, new JSONObject([name: "$user.username storage", user: user.id]))
        })
    }
}
