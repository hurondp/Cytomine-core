package be.cytomine.ontology

import be.cytomine.ModelService
import be.cytomine.command.annotation.AddAnnotationCommand
import be.cytomine.command.annotation.DeleteAnnotationCommand
import be.cytomine.command.annotation.EditAnnotationCommand
import be.cytomine.image.ImageInstance
import be.cytomine.image.server.RetrievalServer
import be.cytomine.project.Project
import be.cytomine.security.User
import com.vividsolutions.jts.geom.Geometry
import com.vividsolutions.jts.io.WKTReader
import com.vividsolutions.jts.io.WKTWriter
import com.vividsolutions.jts.simplify.DouglasPeuckerSimplifier
import grails.converters.JSON
import org.codehaus.groovy.grails.web.json.JSONObject
import be.cytomine.command.AddCommand
import be.cytomine.command.EditCommand
import be.cytomine.command.DeleteCommand
import be.cytomine.Exception.ObjectNotFoundException

class AnnotationService extends ModelService {

    static transactional = true
    def cytomineService
    def transactionService
    def annotationTermService
    def retrievalService
    def suggestedTermService
    def responseService

   boolean saveOnUndoRedoStack = true

    def list() {
        Annotation.list()
    }

    def list(ImageInstance image) {
        Annotation.findAllByImage(image)
    }

    def list(User user) {
        Annotation.findAllByUser(user)
    }

    def list(Project project, List<User> userList, boolean noTerm) {
        if (userList.isEmpty()) return []
        else if (noTerm) {

            def terms = Term.findAllByOntology(project.getOntology())
            def annotationsWithTerms = AnnotationTerm.createCriteria().list {
                inList("term", terms)
                join("annotation")
                createAlias("annotation", "a")
                projections {
                    inList("a.image", project.imagesinstance())
                    groupProperty("annotation.id")
                }
            }
            println "annotationsWithTerms ---> " + annotationsWithTerms.size()
            //inList crash is argument is an empty list so we have to use if/else at this time
            def annotations = null
            if (annotationsWithTerms.size() == 0) {
                annotations = Annotation.createCriteria().list {
                    inList("image", project.imagesinstance())
                    inList("user", userList)

                }
            } else {
                annotations = Annotation.createCriteria().list {
                    inList("image", project.imagesinstance())
                    inList("user", userList)
                    not {
                        inList("id", annotationsWithTerms)
                    }
                }
            }

            return annotations
        } else {
            return Annotation.findAllByImageInListAndUserInList(project.imagesinstance(), userList)
        }
    }

    def list(ImageInstance image, User user) {
        return Annotation.findAllByImageAndUser(image, user)
    }

    def list(Term term) {
        term.annotations()
    }


    def list(Project project, Term term, List<User> userList) {
        def annotationFromTermAndProject = []
        def annotationFromTerm = term.annotations()
        annotationFromTerm.each { annotation ->
            if (annotation.project() != null && annotation.project().id == project.id && userList.contains(annotation.user))
                annotationFromTermAndProject << annotation
        }
        annotationFromTermAndProject
    }

    Annotation get(def id) {
        Annotation.get(id)
    }



    Annotation read(def id) {
        Annotation.read(id)
    }

    def add(def json) {

        User currentUser = cytomineService.getCurrentUser()

        //simplify annotation
        try {
            json.location = new WKTWriter().write(simplifyPolygon(json.location))
        } catch (Exception e) {
            log.error("Cannot simplify:" + e)
        }

        //Start transaction
        transactionService.start()

        //Add annotation user
        json.user = currentUser.id
        //Add Annotation
        log.debug this.toString()
        def result = executeCommand(new AddCommand(user: currentUser), json)
        def annotation = result?.data?.annotation?.id
        log.info "annotation=" + annotation + " json.term=" + json.term
        //Add annotation-term if term
        if (annotation) {
            def term = json.term;
            if (term) {
                term.each { idTerm ->
                    annotationTermService.addAnnotationTerm(annotation, idTerm, currentUser.id, currentUser)
                }
            }
        }

        //Stop transaction
        transactionService.stop()

        //add annotation on the retrieval
        try {if (annotation) indexRetrievalAnnotation(annotation) } catch (Exception e) { log.error "Cannot index in retrieval:" + e.toString()}

        return result
    }

    def update(def json) {

        User currentUser = cytomineService.getCurrentUser()
        def result = executeCommand(new EditCommand(user: currentUser), json)

        if (result.success) {
            Long id = result.annotation.id
            try {updateRetrievalAnnotation(id)} catch (Exception e) { log.error "Cannot update in retrieval:" + e.toString()}
        }
        return result
    }

    def delete(def json) {

        User currentUser = cytomineService.getCurrentUser()

        //Start transaction
        transactionService.start()

        //Delete annotation (+cascade)
        def result = deleteAnnotation(json.id, currentUser)

        //Stop transaction
        transactionService.stop()

        //Remove annotation from retrieval
        Long idAnnotation = result?.annotation?.id
        try {if (idAnnotation) deleteRetrievalAnnotation(idAnnotation) } catch (Exception e) { log.error "Cannot delete in retrieval:" + e.toString()}
        return result
    }


    def deleteAnnotation(def idAnnotation, User currentUser) {
        return deleteAnnotation(idAnnotation, currentUser, true)
    }

    def deleteAnnotation(def idAnnotation, User currentUser, boolean printMessage) {
        log.info "Delete annotation: " + idAnnotation
        Annotation annotation = Annotation.read(idAnnotation)
        if (annotation) {
            //Delete Annotation-Term before deleting Annotation
            annotationTermService.deleteAnnotationTermFromAllUser(annotation, currentUser)

            //Delete Suggested-Term before deleting Annotation
            suggestedTermService.deleteSuggestedTermFromAllUser(annotation, currentUser)
        }
        //Delete annotation
        def json = JSON.parse("{id: $idAnnotation}")
        def result = executeCommand(new DeleteCommand(user: currentUser), json)
        return result
    }


    private indexRetrievalAnnotation(Long id) {
        //index in retrieval (asynchronous)
        RetrievalServer retrieval = RetrievalServer.findByDescription("retrieval")
        log.info "annotation.id=" + id + " stevben-server=" + retrieval
        if (id && retrieval) {
            log.info "index annotation " + id + " on  " + retrieval.url
            retrievalService.indexAnnotationSynchronous(Annotation.read(id))
        }
    }

    private deleteRetrievalAnnotation(Long id) {
        RetrievalServer retrieval = RetrievalServer.findByDescription("retrieval")
        log.info "annotation.id=" + id + " retrieval-server=" + retrieval
        if (id && retrieval) {
            log.info "delete annotation " + id + " on  " + retrieval.url
            retrievalService.deleteAnnotationSynchronous(id)
        }
    }

    private updateRetrievalAnnotation(Long id) {
        RetrievalServer retrieval = RetrievalServer.findByDescription("retrieval")
        log.info "annotation.id=" + id + " retrieval-server=" + retrieval
        if (id && retrieval) {
            log.info "update annotation " + id + " on  " + retrieval.url
            retrievalService.updateAnnotationSynchronous(id)
        }
    }


    private Geometry simplifyPolygon(String form) {

        Geometry annotationFull = new WKTReader().read(form);
        Geometry lastAnnotationFull = annotationFull
        println "points=" + annotationFull.getNumPoints() + " " + annotationFull.getArea();
        println "annotationFull:" + annotationFull.getNumPoints() + " |" + new WKTWriter().write(annotationFull);

        /* Number of point (ex: 500 points) */
        double numberOfPoint = annotationFull.getNumPoints()
        /* Maximum number of point that we would have (500/5 (max 150)=max 100 points)*/
        double rateLimitMax = Math.min(numberOfPoint / 7.5d, 150)
        /* Minimum number of point that we would have (500/10 (min 10 max 100)=min 50 points)*/
        double rateLimitMin = Math.min(Math.max(numberOfPoint / 10d, 10), 100)
        /* Increase value for the increment (allow to converge faster) */
        float incrThreshold = 0.25f
        double increaseIncrThreshold = numberOfPoint / 100d
        float i = 0;
        /* Max number of loop (prevent infinite loop) */
        int maxLoop = 500

        while (numberOfPoint > rateLimitMax && maxLoop > 0) {
            lastAnnotationFull = DouglasPeuckerSimplifier.simplify(annotationFull, i)
            log.debug "annotationFull=" + i + " " + lastAnnotationFull.getNumPoints()
            if (lastAnnotationFull.getNumPoints() < rateLimitMin) break;
            annotationFull = lastAnnotationFull
            i = i + (incrThreshold * increaseIncrThreshold); maxLoop--;
        }

        log.debug "annotationFull good=" + i + " " + annotationFull.getNumPoints() + " |" + new WKTWriter().write(lastAnnotationFull);
        return lastAnnotationFull
    }

    /**
     * Restore domain which was previously deleted
     * @param json domain info
     * @param commandType command name (add/delete/...) which execute this method
     * @param printMessage print message or not
     * @return response
     */
    def restore(JSONObject json, String commandType, boolean printMessage) {
        log.info "#####" + commandType + "=Add"
        restore(Annotation.createFromDataWithId(json),commandType,printMessage)
    }
    def restore(Annotation domain, String commandType, boolean printMessage) {
        //Save new object
        domain.save(flush: true)
        //Build response message
        def response = responseService.createResponseMessage(domain,[domain.id, domain.image?.baseImage?.filename],printMessage,commandType,domain.getCallBack())
        return response
    }
    /**
     * Destroy domain which was previously added
     * @param json domain info
     * @param commandType command name (add/delete/...) which execute this method
     * @param printMessage print message or not
     * @return response
     */
    def destroy(JSONObject json, String commandType, boolean printMessage) {
        //Get object to delete
        log.info "#####" + commandType + "=Delete"
         destroy(Annotation.get(json.id),commandType,printMessage)
    }
    def destroy(Annotation domain, String commandType, boolean printMessage) {
        //Build response message
        def response = responseService.createResponseMessage(domain,[domain.id, domain.image?.baseImage?.filename],printMessage,commandType,domain.getCallBack())
        //Delete object
        domain.delete(flush: true)
        return response
    }

    /**
     * Edit domain which was previously edited
     * @param json domain info
     * @param commandType  command name (add/delete/...) which execute this method
     * @param printMessage  print message or not
     * @return response
     */
    def edit(JSONObject json, String commandType, boolean printMessage) {
        log.info "#####" + commandType + "=Edit"
        //Rebuilt previous state of object that was previoulsy edited
        edit(fillDomainWithData(new Annotation(),json),commandType,printMessage)
    }
    def edit(Annotation domain, String commandType, boolean printMessage) {
        //Build response message
        def response = responseService.createResponseMessage(domain,[domain.id, domain.image?.baseImage?.filename],printMessage,commandType,domain.getCallBack())
        //Save update
        domain.save(flush: true)
        return response
    }

    Annotation createFromData(def json) {
       return Annotation.createFromData(json)
    }

    def retrieve(JSONObject json) {
        Annotation annotation = Annotation.get(json.id)
        if(!annotation) throw new ObjectNotFoundException("Annotation " + json.id + " not found")
        return annotation
    }

}
