const core = require('@actions/core');
const github = require('@actions/github');

import { App } from "octokit";
import { createAppAuth } from "@octokit/auth-app";
import { request } from "@octokit/request";

try {
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

 const app = new App({
   appId: APP_ID,
   privateKey: privateKey,
 });

  const octokit = await app.getInstallationOctokit(56304673);
  console.log(octokit);

  const time = (new Date()).toTimeString();
  core.setOutput("token", time);
  // Get the JSON webhook payload for the event that triggered the workflow
  const payload = JSON.stringify(github.context.payload, undefined, 2)
  console.log(`The event payload: ${payload}`);
} catch (error) {
  core.setFailed(error.message);
}

