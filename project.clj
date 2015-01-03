(defproject rangle "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2371"]]

  :plugins [[lein-cljsbuild "1.0.4-SNAPSHOT"]]

  :source-paths ["src"]

  :cljsbuild {
    :builds [{:id "rangle"
              :source-paths ["src"]
              :compiler {
                :output-to "target/rangle.js"
                :optimizations :whitespace
                :source-map "target/rangle.js.map"}}]})
