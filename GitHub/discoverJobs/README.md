# Discover Jobs

This is a job DSL script to search a GitHub user or organisation for repositories containing Jenkins pipelines.
Any pipelines found will authomatically be added as Jenkins jobs.

Each repository can define multiple pipelines. Any file named `jenkins/<name>/Jenkinsfile`
will cause a **&lt;repository>/&lt;name>** job to be created in Jenkins. If such a pipeline exists,
this script will then look for other "hook" Jenkinsfiles in the `jenkins/<name>` directory as follows:
* `jenkins/<name>/[<n2>.]onBranchCreate.Jenkinsfile` –
  Defines a multibranch pipeline job named **&lt;repository>/&lt;name> - On Branch Create[ - &lt;n2>]**.
  Each branch will only be triggered once: when the corresponding branch in the main multibranch pipeline job is first created.
* `jenkins/<name>/[<n2>.]onBranchDelete.Jenkinsfile` –
  Defines a basic pipeline job named **&lt;repository>/&lt;name> - On Branch Delete[ - &lt;n2>]**.
  It will be triggered each time a branch is deleted from the main multibranch pipeline job.
* `jenkins/<name>/[<n2>.]onRunDelete.Jenkinsfile` –
  Defines a multibranch pipeline job named **&lt;repository>/&lt;name> - On Run Delete[ - &lt;n2>]**.
  Each branch will be triggered whenever a run from the corresponding branch in the main multibranch pipeline job deleted.


## Required Global Bindings

* `ghOwner` – The GitHub user or organisation whose repositories should be searched.
* `httpCredentialsId` – The Jenkins credential ID for HTTP authentication to GitHub.
                        Must be "Username & Password" type, but the password can be a personal access token.
* `sshCredentialsId` – The Jenkins credential ID to use for SSH authentication to GitHub.
                       Must be "SSH private key" type.
* `apiToken` – A GitHub API token to authenticate with when scanning repositories.
               This must be an actual value, not a credentials ID.
