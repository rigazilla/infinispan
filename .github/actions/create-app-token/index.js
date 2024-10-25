import core from "@actions/core";
import github from "@actions/github";
import { createAppAuth } from "@octokit/auth-app";
import { request } from "@octokit/request";


async function aute(auth) {
   const response = await request("GET /repos/{owner}/{repo}/installation", {
      owner: "rigazilla",
      repo: "infinispan",
      request: {
        hook: auth.hook,
      },
    });
    core.info("RRRRRESP: "+response);
}

      // `who-to-greet` input defined in action metadata file
      const privateKey = core.getInput('private-key');
      console.log(`Hello ${privateKey}!`);

    //   const auth = createAppAuth({
    //    appId: APP_ID,
    //    privateKey: privateKey,
    //    request,
    //  });

    //  console.log(auth);
    //  const install = await request("GET /repos/{owner}/{repo}/installation", {
    //    owner: "rigazilla",
    //    repo: "infinispan",
    //    request: {
    //      hook: auth.hook,
    //    },
    //  });

    const auth = createAppAuth({
      appId: 1033848,
      privateKey: privateKey,
      request
    });

    aute(auth, privateKey)

      const time = (new Date()).toTimeString();
      core.setOutput("token", time);
      // Get the JSON webhook payload for the event that triggered the workflow
      const payload = JSON.stringify(github.context.payload, undefined, 2)
      console.log(`The event payload: ${payload}`);

