/* SPDX-License-Identifier: BSD-2-Clause OR GPL-2.0-only */
/* SPDX-FileCopyrightText: Copyright Amazon.com, Inc. or its affiliates. All rights reserved. */
/* This file contains variables and functions that can be shared across different jobs */
import groovy.transform.Field
@Field boolean build_ok = true

def get_portafiducia_download_path() {
    /* Stable Portafiducia tarball */
    def AWS_ACCOUNT_ID = sh (
                script: "aws sts get-caller-identity --query Account --output text | tr -dc 0-9",
                returnStdout: true
              )
    return "s3://libfabric-ci-$AWS_ACCOUNT_ID-us-west-2/portafiducia/portafiducia.tar.gz"
}

def download_and_extract_portafiducia(outputDir) {
    /* Download PortaFiducia tarball from S3 and extract to outputDir */
    def tempPath = "/tmp/portafiducia.tar.gz"
    def downloadPath = this.get_portafiducia_download_path()

    def ret = sh (
        script: "mkdir -p ${outputDir} && aws s3 cp ${downloadPath} ${tempPath} && " +
            "tar xf ${tempPath} -C ${outputDir}",
        returnStatus: true,
    )

    if (ret != 0) {
        unstable('Failed to download and extract PortaFiducia')
    }
}

def install_porta_fiducia() {
    /*
     * Install PortaFiducia in a (new) virtual environment.
     */
    sh '''
        python3 -m venv venv
        . venv/bin/activate
        pip install --upgrade pip
        pip install --upgrade awscli
        pip install -e PortaFiducia
    '''
}

def kill_all_clusters(instance_type, region) {
    def instance_type_without_period = sh(
        script: "echo ${instance_type} | tr -d '.\\n'",
        returnStdout: true
    )
    sh ". venv/bin/activate; ./PortaFiducia/scripts/delete_manual_cluster.py --cluster-name \'*${instance_type_without_period}*\' --region ${region} || true"
}

def wait_for_odcr_capacity(region, instance_count, odcr) {
    sh ". venv/bin/activate; ./PortaFiducia/scripts/wait_for_odcr_capacity.py --region ${region} --odcr-id ${odcr} --required-capacity ${instance_count}"
}

def run_test_orchestrator_once(run_name, build_tag, os, instance_type, instance_count, region, config, odcr, addl_args) {
    /*
     * Run PortaFiducia/tests/test_orchestrator.py with given command line arguments
     */

    /*
     * This is a temporary workaround to deal with clusters not getting cleaned up
     * Attempt to cleanup all instances types in a region when you get the lock.
     * This is required b/c milestones send multiple SIG_TERM, followed by a SIG_KILL after 20s.
     * This stops us from being able to add additional capacity to the Jenkins service.
     */
    kill_all_clusters(instance_type, region)
    wait_for_odcr_capacity(region, instance_count, odcr)

    /*
     * p3dn clusters are getting ICE'ed within an ODCR, when we try to launch them back to back.
     * This is a non-deterministic work around to help us increase our chances of not getting ICE'ed.
     * Worst case, this increases our time to publish results on PR's by 15 minutes.
     */
    if (instance_type == "p3dn.24xlarge") {
        sh "sleep 150"
    }

    def cluster_name = get_cluster_name(build_tag, os, instance_type)
    def args = "--config ${config} --os ${os} --odcr ${odcr} --instance-type ${instance_type} --instance-count ${instance_count} --region ${region} --cluster-name ${cluster_name} ${addl_args} --junit-xml outputs/${cluster_name}.xml"
    def ret = sh (
                    script: ". venv/bin/activate; ./PortaFiducia/tests/test_orchestrator.py ${args}",
                    returnStatus: true
                  )
    if (ret == 65)
        unstable('Scripts exited with status 65')
    else if (ret != 0)
        build_ok = false
    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
        sh "exit ${ret}"
    }
}

def get_random_string(len) {
    def s = sh (
        script: "cat /dev/urandom | LC_ALL=C tr -dc A-Za-z0-9 | head -c ${len}",
        returnStdout: true
    )
    return s
}

def get_cluster_name(build_tag, os, instance_type) {
    /*
     * Compose the cluster name. Pcluster requires a cluster name under 60 characters.
     * cluster name cannot have ".".
     * Jenkins does not allow groovy to use the replace() method
     * of string. Therefore we used shell command sed to replace "." with ""
     */
    build_tag = sh(
                        script: "echo ${build_tag} | sed \"s/^jenkins-//g\" | sed \"s/ //g\"",
                        returnStdout: true
                )

    def cluster_name = sh(
                        script: "echo '${build_tag.take(28)}-${os.take(10)}-${instance_type}-'${get_random_string(8)} | tr -d '.\\n'",
                        returnStdout: true
                     )

    return cluster_name
}


def get_test_stage_with_lock(stage_name, build_tag, os, instance_type, region, lock_label, lock_count, config, odcr, addl_args) {
    /*
     * Generate a single test stage that run test_orchestrator.py with the given parameters.
     * The job will queue until it acquires the given number of locks. The locks will be released
     * after the job finishes.
     * param@ stage_name: the name of the stage
     * param@ build_tag: the BUILD_TAG env generated by Jenkins
     * param@ os: the operating system for the test stage.
     * param@ instance_type: the instance type for the test stage.
     * param@ region: the (default) aws region where the tests are run.
     * param@ lock_label: str, the label of the lockable resources.
     * param@ lock_count: int, the quantity of the lockable resources.
     * param@ config: the name of the PortaFiducia config file
     * param@ odcr: The on demand capacity reservation ID to create instances in
     * param@ addl_args: additional arguments passed to test_orchestrator.py
     * return@: the test stage.
     */
    return {
        stage("${stage_name}") {
            lock(label: lock_label, quantity: lock_count) {
                this.run_test_orchestrator_once(stage_name, build_tag, os, instance_type, lock_count, region, config, odcr, addl_args)
            }
        }
    }
}



return this
