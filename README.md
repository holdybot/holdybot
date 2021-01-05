# This is Holdybot

An application that I wrote on evenings and weekends June/July 2019.
It is used in Price f(x) to manage parking and desk sharing (we call it Parky here! :)). It was an incredible success, 
hence I share it on github.

## What it does?
Check this nice video to understand what it does

https://www.youtube.com/watch?v=J-apTcFxKsk

# Docs

Check wiki for some docs https://github.com/holdybot/holdybot/wiki

# Conferences

Clojure/north 2020 
 
https://www.youtube.com/watch?v=3dE_lQ2qPuE

Talk at Barcamp 2019 Hradec Králové - Czech only 

https://www.youtube.com/watch?v=VgORnv1sMLk

 
# Tech
The app skeleton was generated with Luminus version "3.34", uses Postgres to store the data and is written in 
Clojure/ClojureScript. Although in sources it is called Parky, we usually call it Holdybot now :)

## Prerequisites

You will need [Leiningen][1] 2.0 or above installed.

[1]: https://github.com/technomancy/leiningen

## How to develop this?

Create a dev-config.edn in project's root folder

    ;; WARNING
    ;; The dev-config.edn file is used for local environment variables, such as database credentials.
    ;; This file is listed in .gitignore and will be excluded from version control by Git.
    
    {:dev true
     :port 3000 ;; http server port
     ;; when :nrepl-port is set the application starts the nREPL server on load
     :nrepl-port 7000
     :database-url "jdbc:postgresql://localhost/holdybot?user=holdybot&password=secretPassw0rd"
     :app-name "Holdybot"
     ;; :multitenant-domain "my-multi-tenant-domain.com"
     :jwt-secret "abcdertzujfgasgfretgfdretzhgfrtzh234535643564gfdgfdgd" ;; change me please!
     :root-users #{"tomas@example.com" "tereza@example.com"}
     :smtp {:transport {:host "smpt.example.com"}
                        ;;:user "user@example.com"
                        ;;:pass "password"
                        ;;:port 587}
            :from "Holdybot <Holdybot@example.com>"}
     :hcaptcha  {:sitekey "235435632563563531525_DFGSDSGHDSGH"    ;; hcaptcha, obtain in their ui
                 :secretkey "235435632563563531525_sfasfasdfasfd"}
     :open-id-connect {:azure {:api-key "24325-1243123-12313-12313" ;; generate this in azure dashboard for free
                               :api-secret "SDFSHU6Z5TRTHU65"}
                       :google {:api-key "12312312312-sdfasfasdfasdfasdf.apps.googleusercontent.com" ;; generate this in google cloud console for free
                                :api-secret "fasdfasdfasdfasdfadf"}
                       :linkedin {:api-key "2341FASFDAFSD" ;; generate this in linkedin for developers
                                  :api-secret "23SFDfsdfa324"}
                       :facebook {:api-key "" ;; generate this in facebook for developers
                                  :api-secret ""}}} ;; empty api-key makes the login button disappear
    


To start a web server for the backend development, run:

    lein run 

To start a client dev build, run:

    lein shadow watch app
    
Feel free to connect to server or client repl from your favorite IDE or editor.

On first run, you might need to create a db schema in your postgres db, run migrate fn in user.clj

Run this sql
        INSERT INTO tenant (host, email, activated) VALUES ('localhost:3000', 'youremail@example.com', true); 

Open http://localhost:3000 in your browser and if you have configured hcaptcha and smtp, you should be able to login through email token. If you have configured openid connect (check wiki), it should work for you.

## How to run this?

Create an uberjar (jar with both server and client)

    lein uberjar
    
Run it first time to bootstrap db schema

    java -Dconf=/var/parky/config.edn -jar target/uberjar/parky.jar migrate   
    
Run it as a service (make sure config.edn does not contain :dev nor :nrepl-port keys)

    java -Dconf=/var/parky/config.edn -jar target/uberjar/parky.jar
    
You can build a container or just run the uberjar as you are used to on your platform.
e.g. on linux, one probably creates a systemd service like this (to let it run with limited user with home in /var/parky)

    [Unit]
    Description=parking app jvm backend
    After=syslog.target
    [Service]
    WorkingDirectory=/var/parky
    SyslogIdentifier=parky
    ExecStart=/bin/bash -c "java -Dconf=/var/parky/config.edn -jar /var/parky/parky.jar"
    User=parky
    Type=simple
    [Install]
    WantedBy=multi-user.target        
    
This starts (bundled) http server (wildfly) on desired port. It is a good idea to run some https web server before it and proxy all http requests there.
httpd from Apache works ok, the app reads x-forwarded-host http header that apache by default sets and needs a working record in tenant table that matches the host.

Example apache virtual host (make sure to enable mod_proxy and mod_ssl)

    <IfModule mod_ssl.c>
    <VirtualHost *:443>
            Protocols h2 http/1.1
            ServerName my-greatest-parking-app-for-my-best-company.com
            ErrorLog ${APACHE_LOG_DIR}/parky_error.log
            LogLevel emerg
            CustomLog ${APACHE_LOG_DIR}/parky_access.log combined env=!dontlog
    
            SSLEngine on
    
            SSLCertificateFile    /etc/apache2/ssl/my-greatest-parking-app-for-my-best-company.crt
            SSLCertificateKeyFile /etc/apache2/ssl/my-greatest-parking-app-for-my-best-company.key
    
            #uncomment me when using multitenant mode
            #ProxyPreserveHost On
    
            ProxyPass / http://localhost:3000/
    
            AddOutputFilterByType DEFLATE text/plain
            AddOutputFilterByType DEFLATE text/html
            AddOutputFilterByType DEFLATE text/xml
            AddOutputFilterByType DEFLATE text/css
            AddOutputFilterByType DEFLATE application/javascript
            AddOutputFilterByType DEFLATE text/javascript
            AddOutputFilterByType DEFLATE application/json
            AddOutputFilterByType DEFLATE application/transit+json
    </VirtualHost>
    </IfModule>
    
You need to create a first tenant (even though you'll not use multitenant support). It is enough to fill host, email and activated columns.
Resulting sql might be like this.

    INSERT INTO tenant (host, email, activated) VALUES ('my-greatest-parking-app-for-my-best-company.com', 'myemail@example.com', true); 
    
Since now, you should be able to log in to the app with your email if you have correctly configured the smtp and hcaptcha.
There is an option to configure open id connect login, which is the preferred way of using it. There are currently available providers for Facebook, Azure (Microsot/Office365), Google and Linked in login.

Check the wiki 

## License

Copyright © 2019 Tomas Lamr
