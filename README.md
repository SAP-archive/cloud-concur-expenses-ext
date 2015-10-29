# Concur Expenses Sample

Prerequisites:
--------------

1.    Eclipse installed with SAP HANA Cloud Platform Tools plugins
2.    JDK 1.7 is available as an Installed JRE in *Windows->Preferences->Java->Installed JREs*
3.    SAP HANA Cloud Java Web Tomcat 7 is available as a runtime environment *Windows->Preferences->Server-> Runtime Environments*
4.    [Configured destinations for connecting to Concur API and Auth](#configuring-destinations)

##What is it?

This is a sample showing how you can connect to the Concur API and get a history of expenses.

## How to run it?

Step 1: Clone the Git repository

Step 2: Import the project as a Maven project into your eclipse workspace. 
*Note - Make sure the project uses JDK 1.7. This can be configured in the project build path*

Step 3: Run Maven goal clean install 

Step 4: If you are deploying locally then see [Creating and Deleting Destinations Locally](https://help.hana.ondemand.com/help/frameset.htm?7fa92ffa007346f58491999361928303.html).<br>
If you are deploying on the Cloud then see [Creating and Deleting Destinations on the Cloud](https://help.hana.ondemand.com/help/frameset.htm?94dddf7d9e56401ba1719b7e836d8ee9.html).

Step 5: Build and deploy your application. **Make sure you selected the SAP HANA Cloud Java Web Tomcat 7 as the runtime environment**


## <a name="configuring-destinations"></a> Configuring Destinations
The sample uses two HTTP Connectivity Destinations - one for authentication and one for the API.
Prior to running the project you must have the two destinations configured as described in the [SAP HANA Cloud Platform Destinations Documentation] (https://help.hana.ondemand.com/help/frameset.htm?e4f1d97cbb571014a247d10f9f9a685d.html)

The HTTP API Destination should look like this:


>Name=concur-api<br>
Type=HTTP<br>
URL=https\://www.concursolutions.com/api<br>
ProxyType=Internet<br>
TrustAll=true<br>
CloudConnectorVersion=2<br>
Authentication=NoAuthentication<br>

The HTTP Auth Destination should look like this:
>Name=concur-auth<br>
Type=HTTP<br>
URL=https\://www.concursolutions.com/net2/oauth2/accesstoken.ashx<br>
ProxyType=Internet<br>
TrustAll=true<br>
Description=OAuth Token<br>
Authentication=BasicAuthentication<br>
CloudConnectorVersion=2<br>
User=<b><i>your user email</i></b><br>
Password=<b><i>your user password</i></b><br>
X-ConsumerKey=<b><i>your consumer key</i></b><br>

## Resources

* SAP HANA Cloud Documentation - https://help.hana.ondemand.com/
* Concur API - https://developer.concur.com/api-reference/index.html
* SCN Blog post - <a href="#">Coming soon</a>

## Copyright and license

Copyright 2015 SAP AG

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this work except in compliance with the License. You may obtain a copy of the License in the LICENSE file, or at:

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
