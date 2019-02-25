package com.aws
import groovy.json.JsonSlurper

/**
 Executes the given command and parse the output into object.
 *Assumes the command returns Json as output.
 */
def executeShToObject(String command){
    def output = sh(script: command, returnStdout:true).trim()
    def jsonSlurper = new JsonSlurper()
    return jsonSlurper.parseText(output)
}

/**
 Execute AWS ECR commands.
 Basically calls aws-cli like: aws ecr 'operation' ...
 Example: aws ecr create-repository --repository-name dataops/bi-jobs-worker
 Returns: repositoryUri
 */
def ecr(String operation, String repositoryName){
    try{
        def command = "aws ecr ${operation} --repository-name ${repositoryName}"
        return executeShToObject(command)
    } catch (Exception ex) {
        println("Unable to call ECR ${operation} got Exception: ${ex}")
        return null
    }
}

/**
 Tries to create ECR repo, if fails will try to find it.
 Returns: repositoryUri
 */
def ecrGetOrCreateRepo(String repositoryName){
    def ecrRepositoryUri
    def ecrCreation = ecr('create-repository', repositoryName)
    if(ecrCreation){
        ecrRepositoryUri =  ecrCreation['repository']['repositoryUri']
    } else {
        def ecrDescribe = ecr('describe-repositories', repositoryName)
        ecrRepositoryUri = ecrDescribe.repositories[0]['repositoryUri']
    }
    return ecrRepositoryUri
}

def ecrGetLogin(){
    return sh(script: "aws ecr get-login --no-include-email", returnStdout:true).trim()
}

/**
 Execute AWS ECS command.
 Basically calls aws-cli like: aws ecs 'operation' ...
 Example: aws ecs create-cluster --cluster-name "dwh-bi-jobs"
 Returns: status code
 */
def ecs(String operation, String clusterName){
    try{
        def command = "aws ecs ${operation} --cluster-name ${clusterName}"
        return executeShToObject(command)
    } catch (Exception ex) {
        println("Unable to call ECS ${operation} got Exception: ${ex}")
        return null
    }
}

def ecsCreateCluster(String clusterName){
    def clusterDetails = ecs('create-cluster',clusterName)
    return clusterDetails['cluster']['clusterArn']
}

/**
 Execute AWS logs command.
 Basically calls aws-cli like: aws logs 'operation' ...
 Example: aws logs create-log-group --log-group-name dataops/stepfunctions-bi-jobs
 Returns: status code
 */
def logs(String operation, String logGroupName){
    def status = sh(script:"aws logs ${operation} --log-group-name ${logGroupName}", returnStatus:true)
    println("awsLogs status code is: ${status}")
    return status
}

def ecsRegisterTask(String cliInputJsonFile){
    def command = "aws ecs register-task-definition --cli-input-json file://${cliInputJsonFile}"
    def responseObject = executeShToObject(command)
    def taskDefinitionArn = responseObject.taskDefinition.taskDefinitionArn
    return taskDefinitionArn
}

def logsGetOrCreateLogGroup(String logGroupName){
    def ok = 0
    def alreadyExist = 255
    // 0 and 255 are ok
    def logsCreationStatus = logs('create-log-group', logGroupName)
    if(logsCreationStatus != alreadyExist && logsCreationStatus != ok){
        error("Unable to create CloudWatch log group: ${logGroupName}, return status from aws logs is: ${logsCreationStatus}")
    }
}

/**
 * Executes AWS CloudFormation create stack command
 * @param stackName
 * @param templateFile - path to a CloudFormation template .yaml or .json file
 * @param parameters   - example:
 * java.util.Map parameters = [
 *                              "param1"     : 1,
 *                              "param2"     : "some-value",
 *                            ]
 * @param capabilities - list of capabilities: CAPABILITY_IAM, CAPABILITY_NAMED_IAM, CAPABILITY_AUTO_EXPAND
 * @returns stackId - as String
 */
def cloudFormationCreateStack(String stackName, String templateFile, java.util.Map parameters, java.util.List<String> capabilities=[]){
    def parametersString = ""
    parameters.each{ key, value ->
        parametersString += "ParameterKey=${key},ParameterValue=${value} "
    }
    def capabilitiesString = ""
    capabilities.each { c ->
        capabilitiesString += "${c} "
    }
    def command = "aws cloudformation create-stack --stack-name ${stackName} --capabilities ${capabilitiesString.trim()} --template-body file://${templateFile} --parameters ${parametersString.trim()}"
    def responseObject = executeShToObject(command)

    return responseObject.StackId
}

/**
 * Executes AWS CloudFormation describe stack command
 * @param stackName
 * @returns object representing the return value of the corresponding CLI command
 */
def cloudFormationDescribeStacks(String stackName){
    def command = "aws cloudformation describe-stacks --stack-name ${stackName}"
    def responseObject = executeShToObject(command)
    return responseObject
}

/**
 * Executes AWS CloudFormation update stack command
 * @param stackName
 * @param templateFile - path to a CloudFormation template .yaml or .json file
 * @param parameters   - example:
 * @param capabilities - list of capabilities: CAPABILITY_IAM, CAPABILITY_NAMED_IAM, CAPABILITY_AUTO_EXPAND
 * java.util.Map parameters = [
 *                              "param1"     : 1,
 *                              "param2"     : "some-value",
 *                            ]
 *
 * @returns cli command status code
 */
def cloudFormationUpdateStack(String stackName, String templateFile, java.util.Map parameters, java.util.List<String> capabilities=[]){
    def parametersString = ""
    parameters.each{ key, value ->
        parametersString += "ParameterKey=${key},ParameterValue=${value} "
    }
    def capabilitiesString = ""
    capabilities.each { c ->
        capabilitiesString += "${c} "
    }
    def command = "aws cloudformation update-stack --stack-name ${stackName} --capabilities ${capabilitiesString.trim()} --template-body file://${templateFile} --parameters ${parametersString.trim()}"
    def status = sh(script: command, returnStatus:true)
    println("cloudformation update-stack status code is: ${status}")

    return status
}

/**
 * Executes AWS CloudFormation wait stack-create-completed
 * @param stackName
 *
 * @returns cli command status code
 */
def cloudFormationWaitStackCreateComplete(String stackName){
    def waitCommand = "aws cloudformation wait stack-create-complete --stack-name ${stackName}"
    def status = sh(waitCommand)
    println("cloudformation wait stack-create-complete status code is: ${status}")

    return status
}

/**
 * Checks an AWS CloudFormation stack exists
 * @param stackName
 *
 * @returns true if the given stack exist regardless of its state
 */
boolean cloudFormationStackExist(String stackName){
    def waitCommand = "aws cloudformation describe-stacks --stack-name ${stackName}"
    def status = sh(waitCommand)
    println("cloudformation wait stack-create-complete status code is: ${status}")

    return status != 255 ? true : false
}

/**
 * Executes AWS CloudFormation create or update stack command
 * @param stackName
 * @param templateFile - path to a CloudFormation template .yaml or .json file
 * @param parameters   - example:
 * @param capabilities - list of capabilities: CAPABILITY_IAM, CAPABILITY_NAMED_IAM, CAPABILITY_AUTO_EXPAND
 * java.util.Map parameters = [
 *                              "param1"     : 1,
 *                              "param2"     : "some-value",
 *                            ]
 *
 * @returns cli command status code
 */
def cloudFormationCreateOrUpdateStack(String stackName, String templateFile, java.util.Map parameters, java.util.List<String> capabilities=[]){
    def parametersString = ""
    parameters.each{ key, value ->
        parametersString += "ParameterKey=${key},ParameterValue=${value} "
    }
    def capabilitiesString = ""
    capabilities.each { c ->
        capabilitiesString += "${c} "
    }

    if (cloudFormationStackExist(stackName)){
        println("cloudformation stack ${stackName} exist, executing update-stack command")
        return cloudFormationUpdateStack(stackName, templateFile, parameters, capabilities)
    }
    return  cloudFormationCreateStack(stackName, templateFile, parameters, capabilities)

}