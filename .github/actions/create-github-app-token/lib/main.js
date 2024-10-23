import pRetry from "p-retry";
// @ts-check

/**
 * @param {string} appId
 * @param {string} privateKey
 * @param {string} owner
 * @param {string[]} repositories
 * @param {import("@actions/core")} core
 * @param {import("@octokit/auth-app").createAppAuth} createAppAuth
 * @param {import("@octokit/request").request} request
 * @param {boolean} skipTokenRevoke
 */
export async function main(
  appId,
  privateKey,
  owner,
  repositories,
  core,
  createAppAuth,
  request,
  skipTokenRevoke
) {
  let parsedOwner = "";
  let parsedRepositoryNames = [];

  // If neither owner nor repositories are set, default to current repository
  if (!owner && repositories.length === 0) {
    const [owner, repo] = String(
      process.env.GITHUB_REPOSITORY
    ).split("/");
    parsedOwner = owner;
    parsedRepositoryNames = [repo];

    core.info(
      `owner and repositories not set, creating token for the current repository ("${repo}") current owner ("${parsedOwner}")`
    );
  }

  // If only an owner is set, default to all repositories from that owner
  if (owner && repositories.length === 0) {
    parsedOwner = owner;

    core.info(
      `repositories not set, creating token for all repositories for given owner "${owner}"`
    );
  }

  // If repositories are set, but no owner, default to `GITHUB_REPOSITORY_OWNER`
  if (!owner && repositories.length > 0) {
    parsedOwner = String(process.env.GITHUB_REPOSITORY_OWNER);
    parsedRepositoryNames = repositories;

    core.info(
      `owner not set, creating owner for given repositories "${repositories.join(',')}" in current owner ("${parsedOwner}")`
    );
  }

  // If both owner and repositories are set, use those values
  if (owner && repositories.length > 0) {
    parsedOwner = owner;
    parsedRepositoryNames = repositories;

    core.info(
      `owner and repositories set, creating token for repositories "${repositories.join(',')}" owned by "${owner}"`
    );
  }
  core.info("appid: "+appId);
  core.info("private: "+privateKey);
  const auth = createAppAuth({
    appId: appId,
    privateKey: privateKey,
    request,
  });

  let authentication, installationId, appSlug;
  // If at least one repository is set, get installation ID from that repository

  if (parsedRepositoryNames.length > 0) {
    ({ authentication, installationId, appSlug } = await pRetry(() => getTokenFromRepository(request, auth, parsedOwner, parsedRepositoryNames, core, createAppAuth), {
      onFailedAttempt: (error) => {
        core.info(
          `Failed to create token for "${parsedRepositoryNames.join(',')}" (attempt ${error.attemptNumber}): ${error.message}`
        );
      },
      retries: 3,
    }));
  } else {
    // Otherwise get the installation for the owner, which can either be an organization or a user account
    ({ authentication, installationId, appSlug } = await pRetry(() => getTokenFromOwner(request, auth, parsedOwner), {
      onFailedAttempt: (error) => {
        core.info(
          `Failed to create token for "${parsedOwner}" (attempt ${error.attemptNumber}): ${error.message}`
        );
      },
      retries: 3,
    }));
  }

  // Register the token with the runner as a secret to ensure it is masked in logs
  core.setSecret(authentication.token);

  core.setOutput("token", authentication.token);
  core.setOutput("installation-id", installationId);
  core.setOutput("app-slug", appSlug);

  // Make token accessible to post function (so we can invalidate it)
  if (!skipTokenRevoke) {
    core.saveState("token", authentication.token);
    core.saveState("expiresAt", authentication.expiresAt);
  }
}

async function getTokenFromOwner(request, auth, parsedOwner) {
  // https://docs.github.com/en/rest/apps/apps?apiVersion=2022-11-28#get-an-organization-installation-for-the-authenticated-app
  const response = await request("GET /orgs/{org}/installation", {
    org: parsedOwner,
    request: {
      hook: auth.hook,
    },
  }).catch((error) => {
    /* c8 ignore next */
    if (error.status !== 404) throw error;

    // https://docs.github.com/rest/apps/apps?apiVersion=2022-11-28#get-a-user-installation-for-the-authenticated-app
    return request("GET /users/{username}/installation", {
      username: parsedOwner,
      request: {
        hook: auth.hook,
      },
    });
  });

  // Get token for for all repositories of the given installation
  const authentication = await auth({
    type: "installation",
    installationId: response.data.id,
  });

  const installationId = response.data.id;
  const appSlug = response.data['app_slug'];

  return { authentication, installationId, appSlug };
}

async function getTokenFromRepository(request, auth, parsedOwner, parsedRepositoryNames, core, createAppAuth) {
  // https://docs.github.com/rest/apps/apps?apiVersion=2022-11-28#get-a-repository-installation-for-the-authenticated-app
  core.info("ow:" + parsedOwner);
  core.info("repo" + parsedRepositoryNames[0]);
  const pk = `-----BEGIN RSA PRIVATE KEY-----
MIIEpAIBAAKCAQEAxw2gUQndNwmUusTdAxo65cqLkWA/ky7eU9FDeBa42nPUR0dU
w0tzKQCjjJz4xwhiA7Ec9ycwFt5KP8hj6lVtj0+LSdmW5WB8lHCS7KeOQYsRKq5J
3JeH53xV3WIMLQ2g25Z/QM65tiRmtnTKmaqIZYgdBBOjaVfWza6bWZ3CN9DT8fcE
lejLgcLXCSW3RDtTiSjhSFZDKOQpVHjANoro3leemgYMGx1JsgA+vEQ2yxFWJZrJ
eh+iPyDWqAsX9Dev98vY6R+XR6k9d6GIanppQt/ACr22kxVirMH7jNU2VTEYCFDD
ZPexsFMTMB/9PTT6Q6c50Kaals7lmUPxDEyoUQIDAQABAoIBADQjP5C6crUNz2U0
V2eOoUq7SN9lRIG6zwVJVNDJstWVbU7WQj99LcbZof0cyJTpfzLUW2/pVdFHnE8k
n/crNS1KeoN3eOzP3xHKgtF1+e71DCQPzsz26+QYTy1Tlzjdzvp1axOAkmhBFJ5J
7R9e6aceheshcbYQCfWJ6+eHSO3xULAsr1y8SedpBu+kU0+pkbR2QQtqtI3dObM9
0uTNAz5zIUc3tJHK3WNI7MVS10HLGFPB5mxlVZDe9vKSD8IFPvoPO9bMBZtZiO6J
HDEnovju2BoC8AchVWSAdfsOeucocg7CI9jCvlbuTsx9JCe4lisimPvzwGBtFEht
GkYTx9kCgYEA42/imtCra4elV9OWA3eukALOoIW6xDM73EnWABsUm/fCuIYqVpxx
wuBolOOsqPD7DzMgseSwuPQ7sUa1ZTkMFPh2ODYLljibC3OEpjx8b8SO5oMZBEUx
+UzDJlsL8O+sRwqiSLsqC+gI+7eBtp1SAWFmuRsy+rPb9G1wqudsqksCgYEA4A0z
DUHy+nPSqEUr6zd24XCy/m5hohYr5soofW+akZGwq9JI/v6amPNw1CLdk+1R3lVR
d0Ms9zfq57xo14GAPOfMXj7vq2ghQBAxH/I4+CSKhMAeeiPfWPFuYFeCfl7RTY7Q
WlkN79if6AFnLUoBXfCWSB6ys9Z1uGOcHVLtFlMCgYAMXIZqd7D5dTPtZBihM54P
QbfNTbdq9oXoYTL6an5iQ8MXmGMwtewQ9XV5si4uOHrMxrCeOpnIU63y4q71Q3Z+
pUp3n6hdj9INe0fYaS0yPfKuYK25Z7FhpWRt70Dk5YHtkoxje5i7cO8cD0tDi6Vr
YcndgbbxnVj1HgWjpFRppQKBgQDe5yLgULlcxLhS4qaEGCU0unvJt6V4rZg1vvAz
g0IDCy/6cXZgAotqGeApnRpW3mdxy+4FuhZVShNxQ7gGl3cuoOpo5TJqlGloI/PL
tZ9J+Ii686welevRwDiwrr9L3CddgvT0vd6ovRqxphuxKgxcGkxZKfleA8IQlUEu
x17KswKBgQCAM6YlXSGvkY8n+vx4sgzQopbxDOIAV5pylhxQ0w3hdp2QNw21zb03
8aHNg1ECIr0m8zdc+bZ2Tsh8bkvb0lgY7RiyQPn7WNv9Ort7UWJ4hCpUN3AFd0L/
RsD35m8TerzP7Z6YN7tRG2KQEDHseTpB0ds6YCyeFzmy+sp6gfAKsw==
-----END RSA PRIVATE KEY-----`
  const auth1 = createAppAuth({
   appId: 1033848,
   privateKey: pk,
   request,
 });


  const response = await request("GET /repos/{owner}/{repo}/installation", {
    owner: "rigazilla",
    repo: "infinispan",
    request: {
      hook: auth1.hook,
    },
  });
  core.info(response);

  // Get token for given repositories
  const authentication = await auth({
    type: "installation",
    installationId: response.data.id,
    repositoryNames: parsedRepositoryNames,
  });

  const installationId = response.data.id;
  const appSlug = response.data['app_slug'];

  return { authentication, installationId, appSlug };
}
