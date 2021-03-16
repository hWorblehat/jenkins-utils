# Rotate SSH Key

This is a script to create a new SSH key pair for authenticating Jenkins to GitHub.
The public key is uploaded to GitHub, and the private key is saved to Jenkins' local credentials.
If all succeeds, old private keys are deleted from GitHub, leaving the last 3 in place.

**This script must be run as a "System Groovy" script.**

## Required Global Bindings

* `keyTitle` – The base name for the SSH public key in GitHub. The build number will be appended.
* `keyCredentialId` – The Jenkins credential ID to save the SSH private key under.
* `keyCredentialDescription` – The description to give to the SSH private key Jenkins credential.

## Required Job Environment Variables

* `GITHUB_AUTH_TOKEN` – >A GitHub personal access token for authenticating with the GitHub API
