(defproject parky "1.0.0-SNAPSHOT"

  :description "Parking and desk management app"
  :url "https://github.com/holdybot/holdybot"

  :dependencies [[buddy/buddy-sign "3.0.0"]
                 [cheshire "5.8.1"]
                 [clj-http "3.10.0"]
                 [cljs-ajax "0.8.0"]
                 [clojure.java-time "0.3.2"]
                 [com.cognitect/transit-clj "0.8.313"]
                 [com.draines/postal "2.0.3"]
                 [com.github.scribejava/scribejava-apis "6.7.0"]
                 [com.github.scribejava/scribejava-core "6.7.0"]
                 [com.github.scribejava/scribejava-httpclient-apache "6.7.0"]
                 [com.google.javascript/closure-compiler-unshaded "v20200406" :scope "provided"]
                 [com.google.protobuf/protobuf-java "3.11.1"]
                 [compact-uuids "0.2.0"]
                 [conman "0.8.4"]
                 [cprop "0.1.13"]
                 [expiring-map "0.1.9"]
                 [funcool/struct "1.3.0"]
                 [luminus-immutant "0.2.5"]
                 [luminus-migrations "0.6.6"]
                 [luminus-transit "0.1.1"]
                 [luminus/ring-ttl-session "0.3.2"]
                 [markdown-clj "1.10.0"]
                 [metosin/muuntaja "0.6.4"]
                 [metosin/reitit "0.3.10"]
                 [metosin/ring-http-response "0.9.1"]
                 [mount "0.1.16"]
                 [nrepl "0.6.0"]
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.741" :scope "provided"
                  :exclusions [com.google.javascript/closure-compiler-unshaded
                               org.clojure/google-closure-library
                               org.clojure/google-closure-library-third-party]]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/google-closure-library "0.0-20191016-6ae1f72f" :scope "provided"]
                 [org.clojure/google-closure-library-third-party "0.0-20191016-6ae1f72f" :scope "provided"]
                 [org.clojure/tools.cli "0.4.2"]
                 [org.clojure/tools.logging "0.4.1"]
                 [org.postgresql/postgresql "42.2.9"]
                 [org.webjars.npm/bulma "0.7.4"]
                 [org.webjars.npm/material-icons "0.3.0"]
                 [org.webjars/webjars-locator "0.36"]
                 [org.webjars/webjars-locator-jboss-vfs "0.1.0"]
                 [reagent "0.10.0"]
                 [ring-webjars "0.2.0"]
                 [ring/ring-core "1.7.1"]
                 [ring/ring-defaults "0.3.2"]
                 [selmer "1.12.12"]
                 [thheller/shadow-cljs "2.11.5" :scope "provided"]]

  :min-lein-version "2.0.0"
  
  :source-paths ["src/clj" "src/cljs" "src/cljc"]
  :test-paths ["test/clj"]
  :resource-paths ["resources" "target/cljsbuild"]
  :target-path "target/%s/"
  :main ^:skip-aot parky.core

  :plugins [[lein-shadow "0.1.7"]
            [lein-immutant "2.1.0"]]
  :clean-targets ^{:protect false}
  [:target-path "target/cljsbuild"]
  :shadow-cljs
  {:nrepl {:port 7002}
   :builds
   {:app
    {:target :browser
     :output-dir "target/cljsbuild/public/js"
     :asset-path "/js"
     :modules {:app {:entries [parky.app]}}
     :devtools {:watch-dir "resources/public"}}
    :test
    {:target :node-test
     :output-to "target/test/test.js"
     :autorun true}}}
  
  :npm-deps [[shadow-cljs "2.11.5"]
             [react "16.13.0"]
             [react-dom "16.13.0"]
             [bulma-toast "1.5.1"]
             ["@hcaptcha/react-hcaptcha" "0.1.8"]]

  :profiles
  {:uberjar {:omit-source true
             :prep-tasks ["compile" ["shadow" "release" "app"]]
             
             :aot :all
             :uberjar-name "parky.jar"
             :source-paths ["env/prod/clj" "env/prod/cljs"]
             :resource-paths ["env/prod/resources"]}

   :dev           [:project/dev :profiles/dev]
   :test          [:project/dev :project/test :profiles/test]

   :project/dev  {:jvm-opts ["-Dconf=dev-config.edn"]
                  :dependencies [[binaryage/devtools "0.9.11"]
                                 [cider/piggieback "0.4.1"]
                                 [expound "0.8.4"]
                                 [pjstadig/humane-test-output "0.9.0"]
                                 [prone "1.6.3"]
                                 [ring/ring-devel "1.7.1"]
                                 [ring/ring-mock "0.4.0"]]
                  :plugins      [[com.jakemccrary/lein-test-refresh "0.24.1"]]
                  
                  
                  :source-paths ["env/dev/clj" "env/dev/cljs" "test/cljs"]
                  :resource-paths ["env/dev/resources"]
                  :repl-options {:init-ns user}
                  :injections [(require 'pjstadig.humane-test-output)
                               (pjstadig.humane-test-output/activate!)]}
   :project/test {:jvm-opts ["-Dconf=test-config.edn"]
                  :resource-paths ["env/test/resources"]}
                  
                  

   :profiles/dev {}
   :profiles/test {}})
