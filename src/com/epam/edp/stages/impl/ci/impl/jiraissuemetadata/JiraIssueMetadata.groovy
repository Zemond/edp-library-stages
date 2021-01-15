/* Copyright 2020 EPAM Systems.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

See the License for the specific language governing permissions and
limitations under the License.*/

package com.epam.edp.stages.impl.ci.impl.jiraissuemetadata

import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage
import com.github.jenkins.lastchanges.pipeline.LastChangesPipelineGlobal
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.json.JsonSlurperClassic
import groovy.json.JsonSlurper
import hudson.FilePath

@Stage(name = "create-jira-issue-metadata", buildTool = ["maven", "npm", "dotnet", "gradle", "any"], type = [ProjectType.APPLICATION, ProjectType.AUTOTESTS, ProjectType.LIBRARY])
class JiraIssueMetadata {
    Script script

    def getChanges(workDir) {
        script.dir("${workDir}") {
            def publisher = new LastChangesPipelineGlobal(script).getLastChangesPublisher "LAST_SUCCESSFUL_BUILD", "SIDE", "LINE", true, true, "", "", "", "", ""
            publisher.publishLastChanges()
            return publisher.getLastChanges()
        }
    }

    def getJiraIssueMetadataCrTemplate(platform) {
        script.println("[JENKINS][DEBUG] Getting JiraIssueMetadata CR template")
        def temp = platform.getJsonPathValue("cm", "jim-template", ".data.jim\\.json")
        script.println("[JENKINS][DEBUG] JiraIssueMetadata template has been fetched ${temp}")
        return new JsonSlurperClassic().parseText(temp)
    }

    def getJiraIssueMetadataPayload(platform,name) {
        script.println("[JENKINS][DEBUG] Getting JiraIssueMetadataPayload of ${name} Codebase CR")
        def payload = platform.getJsonPathValue("codebases", name, ".spec.jiraIssueMetadataPayload")
        script.println("[JENKINS][DEBUG] JiraIssueMetadataPayload of ${name} Codebase CR has been fetched - ${payload}")
        return new JsonSlurperClassic().parseText(payload)
    }

    def addCommitId(template, id) {
        if (template.spec.commits == "replace") {
            template.spec.commits = []
        }
        template.spec.commits.add(id)
    }

    def addTicketNumber(template, tickets) {
        if (template.spec.tickets == "replace") {
            template.spec.tickets = []
        }
        template.spec.tickets.addAll(tickets)
    }

    def parseJiraIssueMetadataTemplate(platform,job, template, templateParams, commits, ticketNamePattern, commitMsgPattern) {
        script.println("[JENKINS][DEBUG] Parsing JiraIssueMetadata template")
        template.metadata.name = "${templateParams['codebaseName']}-${templateParams['isTag']}".toLowerCase()
        template.spec.codebaseName = templateParams['codebaseName']
        def jenkinsUrl = platform.getJsonPathValue("edpcomponent", "jenkins", ".spec.url")
        script.println("[JENKINS][DEBUG] jenkinsUrl ${jenkinsUrl}")
        script.println("[JENKINS][DEBUG] commits ${commits}")
        def links = []
        for (commit in commits) {
            def info = commit.getCommitInfo()
            script.println("[JENKINS][DEBUG] Commit message ${info.getCommitMessage()}")
            def tickets = info.getCommitMessage().findAll(ticketNamePattern)
            def id = info.getCommitId()
            if (!tickets) {
                script.println("[JENKINS][DEBUG] No found tickets in ${id} commit")
                continue
            }
            //addCommitId(template, id)
            //addTicketNumber(template, tickets)

            script.println("------")
            script.println(info.getCommitMessage() =~ /(?m)^\[EPMDEDP-\d{4}\]:.*$/)
            (info.getCommitMessage() =~ /(?m)^\[EPMDEDP-\d{4}\]:.*$/).each { match ->
                def linkInfo = [
                        'ticket' : match.find(/EPMDEDP-\d{4}/),
                        'message': match.find(/(?<=\:).*/),
                        'link'   : "${jenkinsUrl}/job/${templateParams['codebaseName']}/job/${job.getParameterValue("BRANCH").toUpperCase()}-Build-${templateParams['codebaseName']}/${script.BUILD_NUMBER}/console"
                ]
                script.println("[JENKINS][DEBUG] Link info: ${linkInfo}")
                links.add(linkInfo)
            }
        }

        def payload = getPayloadField(platform,templateParams['codebaseName'], templateParams['isTag'], templateParams['vcsTag'])
        if (payload == null) {
            template.spec.payload = links
        } else {
            payload.put('links', links)
            template.spec.payload = payload
        }


        script.println("[JENKINS][DEBUG] template ${template}")
        return JsonOutput.toJson(template)
    }

    def getPayloadField(platform,component, version, gitTag) {
        def payload = getJiraIssueMetadataPayload(platform,component)
        if (payload == null) {
            return null
        }

        def values = [
                EDP_COMPONENT: component,
                EDP_VERSION  : version,
                EDP_GITTAG   : gitTag]
        payload.each{x->
            values.each { k, v ->
                payload."${x.key}" = payload."${x.key}".replaceAll(k, v)
            }
        }
        script.println("[JENKINS][DEBUG] payload ${payload}")
        return payload
        //return JsonOutput.toJson(payload)
    }

    def createJiraIssueMetadataCR(platform, path) {
        script.println("[JENKINS][DEBUG] Trying to create JiraIssueMetadata CR")
        platform.apply(path.getRemote())
        script.println("[JENKINS][INFO] JiraIssueMetadata CR has been created")
    }

    def saveTemplateToFile(outputFilePath, template) {
        def jiraIssueMetadataTemplateFile = new FilePath(Jenkins.getInstance().
                getComputer(script.env['NODE_NAME']).
                getChannel(), outputFilePath)
        jiraIssueMetadataTemplateFile.write(template, null)
        return jiraIssueMetadataTemplateFile
    }

    def tryToCreateJiraIssueMetadataCR(workDir, platform, template) {
        if (new JsonSlurperClassic().parseText(template).spec.tickets == "replace") {
            script.println("[JENKINS][DEBUG] No changes. Skip creating JiraIssueMetadata CR")
            return
        }
        def filePath = saveTemplateToFile("${workDir}/jim-template.json", template)
        createJiraIssueMetadataCR(platform, filePath)
    }

    void run(context) {
        try {
            def ticketNamePattern = context.codebase.config.ticketNamePattern
            script.println("[JENKINS][DEBUG] context.codebase.config ${context.codebase.config}")
            script.println("[JENKINS][DEBUG] Ticket name pattern has been fetched ${ticketNamePattern}")
            def changes = getChanges(context.workDir)
            def commits = changes.getCommits()
            if (commits == null) {
                script.println("[JENKINS][INFO] No changes since last successful build. Skip creating JiraIssueMetadata CR")
            } else {
                def template = getJiraIssueMetadataCrTemplate(context.platform)
                script.println("[JENKINS][DEBUG] jim template ${template}")
                def templateParams = [
                        'codebaseName': context.codebase.config.name,
                        'vcsTag'      : context.codebase.vcsTag,
                        'isTag'       : context.codebase.isTag
                ]
                def commitMsgPattern = context.codebase.config.commitMessagePattern
                def parsedTemplate = parseJiraIssueMetadataTemplate(context.platform,context.job, template, templateParams, commits, ticketNamePattern, commitMsgPattern)
                tryToCreateJiraIssueMetadataCR(context.workDir, context.platform, parsedTemplate)
            }
        } catch (Exception ex) {
            script.println("[JENKINS][WARNING] Couldn't correctly finish 'create-jira-issue-metadata' stage due to exception: ${ex}")
        }
    }

}