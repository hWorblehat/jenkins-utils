#!/usr/bin/env groovy

import groovy.json.JsonSlurper

import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey

@Grab('com.jcraft:jsch:0.1.55')
import com.jcraft.jsch.*

@Grab('org.apache.httpcomponents.client5:httpclient5:5.0.3')
import org.apache.hc.client5.http.classic.methods.*
import org.apache.hc.client5.http.impl.classic.*
import org.apache.hc.core5.http.*
import org.apache.hc.core5.http.io.HttpClientResponseHandler
import org.apache.hc.core5.http.io.entity.HttpEntities

class JsonResponseHandler extends AbstractHttpClientResponseHandler<Object> {
	private final JsonSlurper json = new JsonSlurper()

	@Override
	Object handleEntity(HttpEntity entity) {
		return entity.getContent().with { content ->
			return json.parse(content)
		}
	}
}

class GitHub implements Closeable {

	private static final String KEYS_URI = 'https://api.github.com/user/keys'
	private final CloseableHttpClient httpClient = HttpClients.createDefault()
	private final HttpClientResponseHandler<String> defaultResponseHandler = new BasicHttpClientResponseHandler()
	private final HttpClientResponseHandler<Object> jsonResponseHandler = new JsonResponseHandler()

	private final String authHeader

	GitHub(String authToken){
		this.authHeader = "token ${authToken}"
	}

	List<Map<String, ?>> getSshKeys() {
		HttpGet req = new HttpGet(KEYS_URI)
		addHeaders(req)
		return httpClient.execute(req, jsonResponseHandler) as List<Map<String, ?>>
	}

	void addSshKey(String title, String publicKey) {
		HttpPost req = new HttpPost(KEYS_URI)
		addHeaders(req)
		req.setEntity(HttpEntities.create("""\
		{
			"title": "${title}",
			"key": "${publicKey.trim()}"
		}
		""".toString(), ContentType.APPLICATION_JSON))
		httpClient.execute(req, defaultResponseHandler)
	}

	void deleteSshKey(int keyId) {
		HttpDelete req = new HttpDelete("${KEYS_URI}/${keyId}")
		addHeaders(req)
		httpClient.execute(req, defaultResponseHandler)
	}

	@Override
	void close() {
		httpClient.close()
	}

	private void addHeaders(HttpUriRequest req) {
		req.setHeader('Authorization', authHeader)
		req.setHeader('Accept', 'application/vnd.github.v3+json')
	}

}

def oldSysOut = System.out
System.out = out
try {

	Map env = build.getEnvironment(listener)

	String publicKey
	String privateKey

	println "Generating new SSH key pair"
	JSch jsch = new JSch()
	KeyPair kpair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096)
	try {
		ByteArrayOutputStream privOs = new ByteArrayOutputStream()
		kpair.writePrivateKey(privOs)
		privateKey = new String(privOs.toByteArray(), 'UTF-8')

		ByteArrayOutputStream pubOs = new ByteArrayOutputStream()
		kpair.writePublicKey(pubOs, URI.create(env['JENKINS_URL']).host)
		publicKey = new String(pubOs.toByteArray(), 'UTF-8')
	} finally {
		kpair.dispose()
	}

	new GitHub(env['GITHUB_AUTH_TOKEN']).with { gitHub ->

		String newKeyTitle = "${keyTitle}-${build.number}"
		println "Adding new SSH public key to GitHub: ${newKeyTitle}"
		gitHub.addSshKey(newKeyTitle, publicKey)

		println "Saving private key to Jenkins' credentials: ${keyCredentialId}"
		def newKey = new BasicSSHUserPrivateKey(
			CredentialsScope.GLOBAL, keyCredentialId, 'git',
			new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(privateKey),
			'', keyCredentialDescription
		)
		def store = SystemCredentialsProvider.getInstance()
		def oldKey = CredentialsProvider.findCredentialById(
			keyCredentialId, SSHUserPrivateKey.class, build
		)
		if(oldKey) {
			store.updateCredentials(Domain.global(), oldKey, newKey)
		} else {
			store.addCredentials(Domain.global(), newKey)
		}

		for(def key : gitHub.getSshKeys()) {
			def m = key.title =~ /^${keyTitle}-(\d+)$/
			if(m) {
				int index = m.group(1).toInteger()
				if(index < build.number-2 || index > build.number) {
					println "Deleting old SSH public key from GitHub: ${key.title}"
					gitHub.deleteSshKey(key.id)
				}
			}
		}
	}

} finally {
	System.out = oldSysOut
}
