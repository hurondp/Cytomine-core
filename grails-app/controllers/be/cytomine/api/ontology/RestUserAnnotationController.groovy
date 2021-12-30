package be.cytomine.api.ontology

/*
* Copyright (c) 2009-2021. Authors: see NOTICE file.
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

import be.cytomine.Exception.CytomineException
import be.cytomine.Exception.ObjectNotFoundException
import be.cytomine.api.RestController
import be.cytomine.ontology.SharedAnnotation
import be.cytomine.ontology.UserAnnotation
import be.cytomine.project.Project
import be.cytomine.security.SecUser
import grails.converters.JSON
import org.apache.commons.io.IOUtils
import org.codehaus.groovy.grails.web.json.JSONArray
import org.apache.http.HttpResponse
import org.apache.http.NameValuePair
import org.apache.http.client.HttpClient
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.HttpPost
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.message.BasicNameValuePair
import org.restapidoc.annotation.*
import org.restapidoc.pojo.RestApiParamType
import static org.springframework.security.acls.domain.BasePermission.READ


/**
 * Controller for annotation created by user
 */
@RestApi(name = "Ontology | user annotation services", description = "Methods for managing an annotation created by a human user")
class RestUserAnnotationController extends RestController {

    def userAnnotationService
    def termService
    def imageInstanceService
    def secUserService
    def projectService
    def cytomineService
    def annotationListingService
    def reportService
    def imageProcessingService
    def securityACLService
    def abstractImageService

    /**
     * List all annotation with light format
     */
    @RestApiMethod(description="List all annotation (very light format)", listing = true)
    def list() {
        responseSuccess(userAnnotationService.listLightForRetrieval())
    }

    @RestApiMethod(description="Count the number of annotation for the current user")
    @RestApiResponseObject(objectIdentifier = "[total:x]")
    @RestApiParams(params=[
            @RestApiParam(name="id", type="long", paramType = RestApiParamType.PATH,description = "The user id (mandatory)"),
            @RestApiParam(name="project", type="long", paramType = RestApiParamType.PATH,description = "The project id"),
    ])
    def countByUser() {
        SecUser user = secUserService.read(params.id)
        Project project = projectService.read(params.project)

        if(params.project && !project) throw new ObjectNotFoundException("ACL error: domain is null! Unable to process project auth checking")

        if(project){
            securityACLService.checkIsSameUserOrAdminContainer(project, user, cytomineService.currentUser)
        } else {
            securityACLService.checkIsSameUser(user, cytomineService.currentUser)
        }
        responseSuccess([total:userAnnotationService.count(user, project)])
    }

    @RestApiMethod(description="Count the number of annotation in the project")
    @RestApiResponseObject(objectIdentifier = "[total:x]")
    @RestApiParams(params=[
            @RestApiParam(name="project", type="long", paramType = RestApiParamType.PATH,description = "The project id"),
            @RestApiParam(name="startDate", type="long", paramType = RestApiParamType.QUERY,description = "Only count the annotations created after this date (optional)"),
            @RestApiParam(name="endDate", type="long", paramType = RestApiParamType.QUERY,description = "Only count the annotations created before this date (optional)")
    ])
    def countByProject() {
        Project project = projectService.read(params.project)
        securityACLService.check(project, READ)
        Date startDate = params.startDate ? new Date(params.long("startDate")) : null
        Date endDate = params.endDate ? new Date(params.long("endDate")) : null
        responseSuccess([total: userAnnotationService.countByProject(project, startDate, endDate)])
    }

    /**
     * Download report with annotation
     */
    @RestApiMethod(description="Download a report (pdf, xls,...) with user annotation data from a specific project")
    @RestApiResponseObject(objectIdentifier =  "file")
    @RestApiParams(params=[
    @RestApiParam(name="id", type="long", paramType = RestApiParamType.PATH,description = "The project id"),
    @RestApiParam(name="terms", type="list", paramType = RestApiParamType.QUERY,description = "The annotation terms id (if empty: all terms)"),
    @RestApiParam(name="users", type="list", paramType = RestApiParamType.QUERY,description = "The annotation users id (if empty: all users)"),
    @RestApiParam(name="images", type="list", paramType = RestApiParamType.QUERY,description = "The annotation images id (if empty: all images)"),
    @RestApiParam(name="afterThan", type="Long", paramType = RestApiParamType.QUERY, description = "(Optional) Annotations created before this date will not be returned"),
    @RestApiParam(name="beforeThan", type="Long", paramType = RestApiParamType.QUERY, description = "(Optional) Annotations created after this date will not be returned"),
    @RestApiParam(name="format", type="string", paramType = RestApiParamType.QUERY,description = "The report format (pdf, xls,...)")
    ])
    def downloadDocumentByProject() {
        Long afterThan = params.getLong('afterThan')
        Long beforeThan = params.getLong('beforeThan')
        reportService.createAnnotationDocuments(params.long('id'), params.terms, params.boolean("noTerm", false), params.boolean("multipleTerms", false),
                params.users, params.images, afterThan, beforeThan, params.format, response, "USERANNOTATION")
    }

    def bootstrapUtilsService
    def sharedAnnotationService
    /**
     * Add comment on an annotation to other user
     */
    @RestApiMethod(description="Add comment on an annotation to other user and send a mail to users")
    @RestApiResponseObject(objectIdentifier = "empty")
    @RestApiParams(params=[
            @RestApiParam(name="annotation", type="long", paramType = RestApiParamType.PATH,description = "The annotation id"),
            @RestApiParam(name="POST JSON: comment", type="string", paramType = RestApiParamType.QUERY,description = "The comment"),
            @RestApiParam(name="POST JSON: sender", type="long", paramType = RestApiParamType.QUERY,description = "The user id who share the annotation"),
            @RestApiParam(name="POST JSON: subject", type="string", paramType = RestApiParamType.QUERY,description = "The subject of the mail that will be send"),
            @RestApiParam(name="POST JSON: from", type="string", paramType = RestApiParamType.QUERY,description = "The username of the user who send the mail"),
            @RestApiParam(name="POST JSON: receivers", type="list", paramType = RestApiParamType.QUERY,description = "The list of user (id) to send the mail"),
            @RestApiParam(name="POST JSON: emails", type="list", paramType = RestApiParamType.QUERY,required = false, description = "The list of emails to send the mail. Used if receivers is null"),
            @RestApiParam(name="POST JSON: annotationURL ", type="string", paramType = RestApiParamType.QUERY,description = "The URL of the annotation in the image viewer"),
            @RestApiParam(name="POST JSON: shareAnnotationURL", type="string", paramType = RestApiParamType.QUERY,description = "The URL of the comment"),
    ])
    def addComment() {

        UserAnnotation annotation = userAnnotationService.read(params.getLong('annotation'))
        def result = sharedAnnotationService.add(request.JSON, annotation, params)
        if(result) {
            responseResult(result)
        }
    }

    /**
     * Show a single comment for an annotation
     */
    //TODO : duplicated code in AlgoAnnotation
    @RestApiMethod(description="Get a specific comment")
    @RestApiParams(params=[
    @RestApiParam(name="annotation", type="long", paramType = RestApiParamType.PATH,description = "The annotation id"),
    @RestApiParam(name="id", type="long", paramType = RestApiParamType.PATH,description = "The comment id"),
    ])
    def showComment() {
        UserAnnotation annotation = userAnnotationService.read(params.long('annotation'))
        if (!annotation) {
            responseNotFound("Annotation", params.annotation)
        }
        def sharedAnnotation = sharedAnnotationService.read(params.long('id'))
        if (sharedAnnotation) {
            responseSuccess(sharedAnnotation)
        } else {
            responseNotFound("SharedAnnotation", params.id)
        }
    }

    /**
     * List all comments for an annotation
     */
    @RestApiMethod(description="Get all comments on annotation", listing=true)
    @RestApiParams(params=[
    @RestApiParam(name="annotation", type="long", paramType = RestApiParamType.PATH,description = "The annotation id")
    ])
    def listComments() {
        UserAnnotation annotation = userAnnotationService.read(params.long('annotation'))
        if (annotation) {
            responseSuccess(sharedAnnotationService.listComments(annotation))
        } else {
            responseNotFound("Annotation", params.id)
        }
    }

    /**
     * Get a single annotation
     */
    @RestApiMethod(description="Get a user annotation")
    @RestApiParams(params=[
    @RestApiParam(name="id", type="long", paramType = RestApiParamType.PATH,description = "The annotation id")
    ])
    def show() {
        UserAnnotation annotation = userAnnotationService.read(params.long('id'))
        if (annotation) {
            responseSuccess(annotation)
        }
        else responseNotFound("Annotation", params.id)
    }


    /**
     * Add annotation created by user
     */
    @RestApiMethod(description="Add an annotation created by user")
    @RestApiParams(params=[
            @RestApiParam(name="POST JSON: project", type="long", paramType = RestApiParamType.PATH, description = "The project id where this annotation belongs"),
            @RestApiParam(name="POST JSON: image", type="long", paramType = RestApiParamType.QUERY, description = "The image instance id where this annotation belongs"),
            @RestApiParam(name="POST JSON: location", type="string", paramType = RestApiParamType.QUERY, description = "The WKT geometrical description of the annotation"),
            @RestApiParam(name="POST JSON: term", type="long", paramType = RestApiParamType.QUERY, required = false, description = "Term id to associate with this annotation"),
            @RestApiParam(name="POST JSON: minPoint", type="int", paramType = RestApiParamType.QUERY, required = false, description = "Minimum number of point that constitute the annotation"),
            @RestApiParam(name="POST JSON: maxPoint", type="int", paramType = RestApiParamType.QUERY, required = false, description = "Maximum number of point that constitute the annotation")
    ])
    def add(){
        def json = request.JSON
        if (json instanceof JSONArray) {
            responseResult(addMultiple(userAnnotationService, json))
        } else {
            responseResult(addOne(userAnnotationService, json))
        }
    }


    /**
     * Get annotation user crop (image area that frame annotation)
     * (Use this service if you know the annotation type)
     */
    @RestApiMethod(description="Get annotation user crop (image area that frame annotation)")
    @RestApiResponseObject(objectIdentifier = "file")
    @RestApiParams(params=[
            @RestApiParam(name="id", type="long", paramType = RestApiParamType.PATH,description = "The annotation id"),
            @RestApiParam(name="maxSize", type="int", paramType = RestApiParamType.PATH,description = "Maximum size of the crop image (w and h)"),
            @RestApiParam(name="zoom", type="int", paramType = RestApiParamType.PATH,description = "Zoom level"),
            @RestApiParam(name="draw", type="boolean", paramType = RestApiParamType.PATH,description = "Draw annotation form border on the image"),
            @RestApiParam(name="thickness", type="int", paramType = RestApiParamType.QUERY, description = " If draw used, set the thickness of the geometry contour on the crop.", required = false),
            @RestApiParam(name="color", type="string", paramType = RestApiParamType.QUERY, description = " If draw used, set the color of the geometry contour on the crop. Color are hexadecimal value", required = false),
            @RestApiParam(name="square", type="boolean", paramType = RestApiParamType.QUERY, description = " If draw used, try to extends the ROI around the crop to have a square.", required = false),
            @RestApiParam(name="complete", type="boolean", paramType = RestApiParamType.PATH,description = "Do not simplify the annotation form")
    ])
    def crop() {
        UserAnnotation annotation = UserAnnotation.read(params.long("id"))
        if (annotation) {
            String url = annotation.toCropURL(params)
            if(url.length()<3584){
                log.info "redirect to ${url}"
                redirect (url : url)
            } else {
                def parameters = annotation.toCropParams(params)
                url = abstractImageService.getCropIMSUrl(parameters)

                //POST request
                URL destination = new URL(url)

                log.info "URL too long "+url.length()+". Post request to ${destination.protocol}://${destination.host}${destination.path}"

                HttpClient httpclient = new DefaultHttpClient();
                HttpPost httppost = new HttpPost("${destination.protocol}://${destination.host}${destination.path}");

                def queries = destination.query.split("&")
                List<NameValuePair> params = new ArrayList<NameValuePair>(queries.size());
                for(String parameter : queries){
                    String[] tmp = parameter.split('=');
                    params.add(new BasicNameValuePair(tmp[0], URLDecoder.decode(tmp[1])));
                }
                httppost.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));

                HttpResponse httpResponse = httpclient.execute(httppost);
                InputStream instream = httpResponse.getEntity().getContent()

                byte[] bytesOut = IOUtils.toByteArray(instream);
                response.contentLength = bytesOut.length;
                response.setHeader("Connection", "Keep-Alive")
                response.setHeader("Accept-Ranges", "bytes")
                if(parameters.format == "png") {
                    response.setHeader("Content-Type", "image/png")
                } else {
                    response.setHeader("Content-Type", "image/jpeg")
                }
                response.getOutputStream() << bytesOut
                response.getOutputStream().flush()
            }
        } else {
            responseNotFound("Annotation", params.id)
        }

    }

    //TODO:APIDOC
    def cropMask () {
        UserAnnotation annotation = UserAnnotation.read(params.long("id"))
        if (annotation) {
            params.mask = true
            // redirect to AbstractImageController crop
            String url = annotation.toCropURL(params)
            if(url.length()<3584){
                log.info "redirect to ${url}"
                redirect (url : url)
            } else {
                def parameters = annotation.toCropParams(params)
                url = abstractImageService.getCropIMSUrl(parameters)

                //POST request
                URL destination = new URL(url)

                log.info "URL too long "+url.length()+". Post request to ${destination.protocol}://${destination.host}${destination.path}"

                HttpClient httpclient = new DefaultHttpClient();
                HttpPost httppost = new HttpPost("${destination.protocol}://${destination.host}${destination.path}");

                def queries = destination.query.split("&")
                List<NameValuePair> params = new ArrayList<NameValuePair>(queries.size());
                for(String parameter : queries){
                    String[] tmp = parameter.split('=');
                    params.add(new BasicNameValuePair(tmp[0], URLDecoder.decode(tmp[1])));
                }
                httppost.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));

                HttpResponse httpResponse = httpclient.execute(httppost);
                InputStream instream = httpResponse.getEntity().getContent()

                byte[] bytesOut = IOUtils.toByteArray(instream);
                response.contentLength = bytesOut.length;
                response.setHeader("Connection", "Keep-Alive")
                response.setHeader("Accept-Ranges", "bytes")
                if(parameters.format == "png") {
                    response.setHeader("Content-Type", "image/png")
                } else {
                    response.setHeader("Content-Type", "image/jpeg")
                }
                response.getOutputStream() << bytesOut
                response.getOutputStream().flush()
            }
        } else {
            responseNotFound("UserAnnotation", params.id)
        }

    }

    //TODO:APIDOC
    def cropAlphaMask () {
        UserAnnotation annotation = UserAnnotation.read(params.long("id"))
        if (annotation) {
            params.alphaMask = true
            // Quickfix : bypass AbstractImageController crop and redirect to IMS
            String redirection = abstractImageService.crop(annotation.toCropParams(params), (new URI(annotation.toCropURL(params))).query)
            log.info "redirect to "+redirection


            if(redirection.length()<2000){
                log.info "redirect $redirection"
                redirect (url : redirection)
            } else {
                URL url = new URL(redirection)

                log.info "URL too long "+redirection.length()+". Post request to ${url.protocol}://${url.host}${url.path}"

                def queries = url.query.split("&")
                List<NameValuePair> parameters = new ArrayList<NameValuePair>(queries.size());
                for(String parameter : queries){
                    String[] tmp = parameter.split('=');
                    parameters.add(new BasicNameValuePair(tmp[0], URLDecoder.decode(tmp[1])));
                }

                org.apache.http.client.HttpClient httpclient = new DefaultHttpClient();
                HttpPost httppost = new HttpPost("${url.protocol}://${url.host}${url.path}");

                httppost.setEntity(new UrlEncodedFormEntity(parameters, "UTF-8"));

                HttpResponse httpResponse = httpclient.execute(httppost);
                InputStream instream = httpResponse.getEntity().getContent()

                byte[] bytesOut = IOUtils.toByteArray(instream);
                response.contentLength = bytesOut.length;
                response.setHeader("Connection", "Keep-Alive")
                response.setHeader("Accept-Ranges", "bytes")
                if(params.format == "png") {
                    response.setHeader("Content-Type", "image/png")
                } else {
                    response.setHeader("Content-Type", "image/jpeg")
                }
                response.getOutputStream() << bytesOut
                response.getOutputStream().flush()
            }

        } else {
            responseNotFound("UserAnnotation", params.id)
        }

    }




    public Object addOne(def service, def json) {
        def minPoint = json.minPoint ? Double.parseDouble(json.minPoint.toString()) : null
        def maxPoint = json.maxPoint ? Double.parseDouble(json.maxPoint.toString()) : null
        if(minPoint == null) minPoint = params.getLong('minPoint')
        if(maxPoint == null) maxPoint = params.getLong('maxPoint')

        def result = userAnnotationService.add(json,minPoint,maxPoint)
        return result
    }

    /**
     * Update annotation created by user
     */
    @RestApiMethod(description="Update an annotation")
    @RestApiParams(params=[
    @RestApiParam(name="id", type="long", paramType = RestApiParamType.PATH,description = "The annotation id")
    ])
    def update() {
        def json = request.JSON
        try {
            def domain = userAnnotationService.retrieve(json)
            def result = userAnnotationService.update(domain,json)
            responseResult(result)
        } catch (CytomineException e) {
            log.error(e)
            response([success: false, errors: e.msg], e.code)
        }
    }

    /**
     * Delete annotation created by user
     */
    @RestApiMethod(description="Delete an annotation")
    @RestApiParams(params=[
    @RestApiParam(name="id", type="long", paramType = RestApiParamType.PATH,description = "The annotation id")
    ])
    def delete() {
        def json = JSON.parse("{id : $params.id}")
        delete(userAnnotationService, json,null)
    }
}
