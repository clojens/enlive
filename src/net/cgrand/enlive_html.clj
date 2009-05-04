;   Copyright (c) Christophe Grand, 2009. All rights reserved.

;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this 
;   distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns net.cgrand.enlive-html
  "enlive-html is a selector-based templating engine."
  (:require [net.cgrand.xml :as xml])
  (:require [clojure.zip :as z])
  (:require [net.cgrand.enlive-html.state-machine :as sm])
  (:use [clojure.contrib.test-is :as test-is :only [set-test with-test is are]]))

;; EXAMPLES: see net.cgrand.enlive-html.examples

;; HTML I/O stuff

(defn- startparse-tagsoup [s ch]
  (let [p (org.ccil.cowan.tagsoup.Parser.)]
    (.setFeature p "http://www.ccil.org/~cowan/tagsoup/features/default-attributes" false)
    (.setFeature p "http://www.ccil.org/~cowan/tagsoup/features/cdata-elements" true)
    (.setContentHandler p ch)
    (.parse p s)))

(defn- load-html-resource 
 "Loads and parse an HTML resource and closes the stream."
 [stream] 
  (list 
    (with-open [#^java.io.Closeable stream stream]
      (xml/parse (org.xml.sax.InputSource. stream) startparse-tagsoup))))

(defmulti html-resource "Loads an HTML resource, returns a seq of nodes." type)

(defmethod html-resource clojure.lang.IPersistentMap
 [xml-data]
  (list xml-data))

(defmethod html-resource clojure.lang.IPersistentCollection
 [nodes]
  (seq nodes))

(defmethod html-resource String
 [path]
  (load-html-resource (-> (clojure.lang.RT/baseLoader) (.getResourceAsStream path))))

(defmethod html-resource java.io.File
 [#^java.io.File file]
  (load-html-resource (java.io.FileInputStream. file)))

(defmethod html-resource java.io.Reader
 [reader]
  (load-html-resource reader))

(defmethod html-resource java.io.InputStream
 [stream]
  (load-html-resource stream))

(defmethod html-resource java.net.URL
 [#^java.net.URL url]
  (load-html-resource (.getContent url)))

(defmethod html-resource java.net.URI
 [#^java.net.URI uri]
  (html-resource (.toURL uri)))


(defn- xml-str
 "Like clojure.core/str but escapes < > and &."
 [x]
  (-> x str (.replace "&" "&amp;") (.replace "<" "&lt;") (.replace ">" "&gt;")))
  
(defn- attr-str
 "Like clojure.core/str but escapes < > & and \"."
 [x]
  (-> x str (.replace "&" "&amp;") (.replace "<" "&lt;") (.replace ">" "&gt;") (.replace "\"" "&quot;")))

(def *self-closing-tags* #{:area :base :basefont :br :hr :input :img :link :meta})

(declare emit)

(defn- emit-attrs [attrs]
  (mapcat (fn [[k v]]
            [" " (name k) "=\"" (attr-str v) "\""]) attrs))

(defn- emit-tag [tag]
  (let [name (-> tag :tag name)]
    (concat ["<" name]
      (emit-attrs (:attrs tag))
      (if-let [s (seq (:content tag))]
        (concat [">"] (mapcat emit s) ["</" name ">"])
        (if (*self-closing-tags* tag) 
          [" />"]
          ["></" name ">"])))))

(defn- annotations [x]
  (-> x meta ::annotations))

(defn- emit [node]
  (if (map? node) 
    ((:emit (annotations node) emit-tag) node)
    [(xml-str node)]))

(defn- emit-root [node]
  (if-let [preamble (-> node meta ::preamble)]
    (cons preamble (emit node))
    (emit node)))
  
(defn emit* [node-or-nodes]
  (if (map? node-or-nodes) (emit-root node-or-nodes) (mapcat emit-root node-or-nodes)))

(defn- emitter [{:keys [tag content attrs] :as node}]
  (let [name (name tag)
        attrs-str (apply str (emit-attrs attrs))
        open (str "<" name attrs-str ">")
        close (str "</" name ">")
        empty [(if (*self-closing-tags* tag)
                 (str "<" name attrs-str " />")
                 (str open close))]
        open [open]
        close [close]
        full [(apply str (emit-tag node))]]
    (fn [elt]
      (cond
        (= node elt) full
        (and (= tag (:tag elt)) (= attrs (:attrs elt)))
          (if-let [content (seq (:content elt))]
            (concat open (mapcat emit content) close)
            empty)
        :else (emit-tag elt)))))

(defn annotate [node]
  (if (map? node)
    (let [node (update-in node [:content] #(map annotate %))] 
      (vary-meta node assoc ::annotations {:emit (emitter node)}))
    node))
      
;; utilities

(defn- not-node? [x]
  (cond (string? x) false (map? x) false :else true))

(defn- flatten [x]
  (remove not-node? (tree-seq not-node? seq x)))
  
(defn flatmap [f xs]
  (flatten (map f xs)))

(defn attr-values 
 "Returns the whitespace-separated values of the specified attr as a set."
 [node attr]
  (disj (set (-> node :attrs (attr "") str (.split "\\s+"))) ""))

;; selector syntax
(defn- simplify-associative [[op & forms]]
  (if (next forms)
    (cons op (mapcat #(if (and (seq? %) (= op (first %))) (rest %) (list %)) forms)) 
    (first forms)))

(defn- emit-union [forms]
  (simplify-associative (cons `sm/union forms)))

(defn- emit-intersection [forms]
  (simplify-associative (cons `sm/intersection forms)))

(defn- emit-chain [forms]
  (simplify-associative (cons `sm/chain forms)))

(with-test
  (defn- compile-keyword [kw]
    (let [[[first-letter :as tag-name] :as segments] (.split (name kw) "(?=[#.])")
          tag-pred (when-not (contains? #{nil \* \# \.} first-letter) [`(tag= ~(keyword tag-name))])
          ids-pred (for [s segments :when (= \# (first s))] `(id= ~(subs s 1)))
          classes (set (for [s segments :when (= \. (first s))] (subs s 1)))
          class-pred (when (seq classes) [`(has-class ~@classes)])
          all-preds (concat tag-pred ids-pred class-pred)] 
      (emit-intersection (or (seq all-preds) [`any]))))

  (are (= _2 (compile-keyword _1))
    :foo `(tag= :foo)
    :* `any
    :#id `(id= "id")
    :.class1 `(has-class "class1")
    :foo#bar.baz1.baz2 `(sm/intersection (tag= :foo) (id= "bar") (has-class "baz1" "baz2"))))
    
(declare compile-step)

(defn- compile-union [s]
  (emit-union (map compile-step s)))      
    
(defn- compile-intersection [s]
  (emit-intersection (map compile-step s)))      

(defn compile-step [s]
  (cond
    (keyword? s) (compile-keyword s)    
    (set? s) (compile-union s)    
    (vector? s) (compile-intersection s)
    :else s))

(defn- compile-chain [s]
  (let [[child-ops [step & next-steps :as steps]] (split-with #{:>} s)
        next-chain (when (seq steps)
                     (if (seq next-steps)
                       (emit-chain [(compile-step step) (compile-chain next-steps)])
                       (compile-step step)))]
    (if (seq child-ops)
      next-chain      
      (emit-chain [`sm/descendants-or-self next-chain])))) 

(defn compile-selector [s]
  (cond
    (set? s) (emit-union (map compile-selector s))
    (vector? s) (compile-chain s)
    :else s))

;; core 
  
(defn- children-locs [loc]
  (take-while identity (iterate z/right (z/down loc))))

(defn- transform-loc [loc previous-state transformation]
  (if (z/branch? loc)
    (let [state (sm/step previous-state loc)
          children (flatmap #(transform-loc % state transformation) (children-locs loc))
          node (if (= children (z/children loc)) 
                 (z/node loc) 
                 (z/make-node loc (z/node loc) children))]
      (if (sm/accept? state)
        (transformation node)
        node))
    (z/node loc)))

(defn transform [nodes [state transformation]]
  (flatmap #(transform-loc (z/xml-zip %) state (or transformation (constantly nil))) nodes))

(defn at* [nodes & rules]
  (reduce transform nodes (partition 2 rules)))

(defmacro selector
 "Turns the selector into clojure code." 
 [selector]
  (compile-selector selector))

(defmacro selector-step
 "Turns the selector step into clojure code." 
 [selector-step]
  (compile-step selector-step))

(defmacro at [node & rules]
  `(at* [~node] ~@(map #(%1 %2) (cycle [#(list `selector %) identity]) rules)))

(defn select* [nodes state]
  (let [select1 
         (fn select1 [loc previous-state] 
           (when-let [state (and (z/branch? loc) (sm/step previous-state loc))]
             (concat (when (sm/accept? state) (list (z/node loc)))
               (mapcat #(select1 % state) (children-locs loc)))))]
    (mapcat #(select1 (z/xml-zip %) state) nodes)))
      
(defmacro select
 "Returns the seq of nodes and sub-nodes matched by the specified selector."
 [nodes selector]
  `(select* ~nodes (selector ~selector)))

;; main macros

(defmacro transformation
 ([] `identity)
 ([form] form)
 ([form & forms] `(fn [node#] (at node# ~form ~@forms))))

(defmacro snippet* [nodes args & forms]
  `(let [nodes# (map annotate ~nodes)]
     (fn ~args
       (flatmap (transformation ~@forms) nodes#))))
    
(defmacro snippet 
 "A snippet is a function that returns a seq of nodes."
 [source selector args & forms]
  `(snippet* (select (html-resource ~source) ~selector) ~args ~@forms))  

(defmacro template 
 "A template returns a seq of string."
 ([source args & forms]
   `(comp emit* (snippet* (html-resource ~source) ~args ~@forms))))

(defmacro defsnippet
 "Define a named snippet -- equivalent to (def name (snippet source selector args ...))."
 [name source selector args & forms]
 `(def ~name (snippet ~source ~selector ~args ~@forms)))
   
(defmacro deftemplate
 "Defines a template as a function that returns a seq of strings." 
 [name source args & forms] 
  `(def ~name (template ~source ~args ~@forms)))

(defmacro defsnippets
 [source & specs]
  (let [xml (html-resource source)]
   `(do
     ~@(map (fn [[name selector args & forms]]
              `(def ~name (snippet ~xml ~selector ~args ~@forms)))
         specs))))

;; test utilities
(defn- htmlize* [node]
  (cond
    (map? node)
      (-> node
        (assoc-in [:attrs :class] (attr-values node :class))
        (update-in [:content] (comp htmlize* seq)))
    (or (coll? node) (seq? node))
      (map htmlize* node)
    :else node))

(defn- htmlize [s]
  (htmlize* 
    (if (string? s)
      (html-resource (java.io.StringReader. s))
      s))) 

(defn html-src [s]
  (first (html-resource (java.io.StringReader. s))))

(defn- same? [& xs]
  (apply = (map htmlize xs)))

(defn- elt 
 ([tag] (elt tag nil))
 ([tag attrs & content]
   {:tag tag
    :attrs attrs
    :content content}))

(defmacro #^{:private true} 
 is-same
 [& forms]
 `(is (same? ~@forms)))

(defmacro sniptest
 "A handy macro for experimenting at the repl" 
 [source-string & forms]
  `(apply str (emit* ((transformation ~@forms) (html-src ~source-string))))) 

;; transformations

(defn content
 "Replaces the content of the node. Values can be nodes or nested collection of nodes." 
 [& values]
  #(assoc % :content (flatten values)))

(defn html-content
 "Replaces the content of the node. Values are strings containing html code."
 [& values]
  #(let [content (-> (apply str "<bogon>" values) java.io.StringReader. html-resource first :content)]
     (assoc % :content content))) 

(defn wrap 
 ([tag] (wrap tag nil))
 ([tag attrs]
   #(array-map :tag tag :attrs attrs :content [%])))

(def unwrap :content)

(defn set-attr
 "Assocs attributes on the selected node."
 [& kvs]
  #(assoc % :attrs (apply assoc (:attrs % {}) kvs)))
     
(defn remove-attr 
 "Dissocs attributes on the selected node."
 [& attr-names]
  #(assoc % :attrs (apply dissoc (:attrs %) attr-names)))
    
(defn add-class
 "Adds the specified classes to the selected node." 
 [& classes]
  #(let [classes (into (attr-values % :class) classes)]
     (assoc-in % [:attrs :class] (apply str (interpose \space classes)))))

(defn remove-class 
 "Removes the specified classes from the selected node." 
 [& classes]
  #(let [classes (apply disj (attr-values % :class) classes)
         attrs (:attrs %)
         attrs (if (empty? classes) 
                 (dissoc attrs :class) 
                 (assoc attrs :class (apply str (interpose \space classes))))]
     (assoc % :attrs attrs)))

(defn do->
 "Chains (composes) several transformations. Applies functions from left to right." 
 [& fns]
  #(reduce (fn [nodes f] (flatmap f nodes)) [%] fns))

(defmacro clone-for
 [comprehension & forms]
  `(fn [node#]
     (for ~comprehension ((transformation ~@forms) node#))))

(defn append
 "Appends the values to the actual content."
 [& values]
  #(assoc % :content (concat (:content %) (flatten values)))) 

(defn prepend
 "Prepends the values to the actual content."
 [& values]
  #(assoc % :content (concat (flatten values) (:content %)))) 

(defn after
 "Inserts the values after the current element."
 [& values]
  #(cons % (flatten values)))

(defn before
 "Inserts the values before the current element."
 [& values]
  #(concat (flatten values) [%]))

(defn substitute
 "Replaces the current element."
 [& values]
 (constantly (flatten values)))

(defmacro move
 "Takes all nodes (under the current element) matched by src-selector, removes
  them and combines them with the elements matched by dest-selector.
  By default, destination elements are replaced." 
 ([src-selector dest-selector] `(move ~src-selector ~dest-selector substitute))
 ([src-selector dest-selector combiner]
  `(fn [node#]
     (let [nodes# (select [node#] ~src-selector)]
       (at node#
         ~src-selector nil
         ~dest-selector (apply ~combiner nodes#)))))) 
     
(defn xhtml-strict* [node]
  (-> node
    (assoc-in [:attrs :xmlns] "http://www.w3.org/1999/xhtml")
    (vary-meta assoc ::preamble 
      "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n")))  

(defmacro xhtml-strict
 "Adds xhtml-strict's DTD." 
 [& forms]
  `(do-> (transformation ~@forms) xhtml-strict*)) 

;; predicates utils
(defn pred 
 "Turns a predicate function on elements into a predicate-step usable in selectors."
 [f]
  (sm/pred #(f (z/node %))))

;; predicates
(defn- test-step [expected state node]
  (= expected (boolean (sm/accept? (sm/step state (z/xml-zip node))))))

(def any (pred (constantly true)))

(with-test
  (defn tag= 
   "Selector predicate, :foo is as short-hand for (tag= :foo)."
   [tag-name]
    (pred #(= (:tag %) tag-name)))
    
  (are (test-step _1 _2 _3)
    true (tag= :foo) (elt :foo)
    false (tag= :bar) (elt :foo)))

(with-test
  (defn id=
   "Selector predicate, :#foo is as short-hand for (id= \"foo\")."
   [id]
    (pred #(= (-> % :attrs :id) id)))

  (are (test-step _1 _2 _3)
    true (id= "foo") (elt :div {:id "foo"})
    false (id= "bar") (elt :div {:id "foo"})
    false (id= "foo") (elt :div)))

(with-test  
  (defn attr? 
   "Selector predicate, tests if the specified attributes are present."
   [& kws]
    (pred #(every? (-> % :attrs keys set) kws)))

  (are (test-step _1 _2 _3)
    true (attr? :href) (elt :a {:href "http://cgrand.net/"})
    false (attr? :href) (elt :a {:name "toc"})
    false (attr? :href :title) (elt :a {:href "http://cgrand.net/"})
    true (attr? :href :title) (elt :a {:href "http://cgrand.net/" :title "home"})))
  
(defn- every?+ [pred & colls]
  (every? #(apply pred %) (apply map vector colls))) 

(defn- multi-attr-pred 
 [single-attr-pred]
  (fn [& kvs]
    (let [ks (take-nth 2 kvs)
          vs (take-nth 2 (rest kvs))]
      (pred #(when-let [attrs (:attrs %)]
               (every?+ single-attr-pred (map attrs ks) vs))))))           

(with-test
  (def #^{:doc "Selector predicate, tests if the specified attributes have the specified values."} 
   attr= 
    (multi-attr-pred =))
    
  (are (test-step _1 _2 (elt :a {:href "http://cgrand.net/" :title "home"}))
    true (attr= :href "http://cgrand.net/")
    false (attr= :href "http://clojure.org/")
    false (attr= :href "http://cgrand.net/" :name "home") 
    false (attr= :href "http://cgrand.net/" :title "homepage")
    true (attr= :href "http://cgrand.net/" :title "home")))

(defn attr-has
 "Selector predicate, tests if the specified whitespace-seperated attribute contains the specified values. See CSS ~="
 [attr & values]
  (pred #(every? (attr-values % attr) values)))
 
(defn has-class 
 "Selector predicate, :.foo.bar is as short-hand for (has-class \"foo\" \"bar\")."
 [& classes]
  (apply attr-has :class classes)) 

(defn- starts-with? [#^String s #^String prefix]
  (and s (.startsWith s prefix)))

(defn- ends-with? [#^String s #^String suffix]
  (and s (.endsWith s suffix)))

(defn- contains-substring? [#^String s #^String substring]
  (and s (<= 0 (.indexOf s substring))))

(with-test
  (def #^{:doc "Selector predicate, tests if the specified attributes start with the specified values. See CSS ^= ."} 
   attr-starts
    (multi-attr-pred starts-with?))

  (are (test-step _1 _2 (elt :a {:href "http://cgrand.net/" :title "home"}))
    true (attr-starts :href "http://cgr")
    false (attr-starts :href "http://clo")
    false (attr-starts :href "http://cgr" :name "ho")
    false (attr-starts :href "http://cgr" :title "x") 
    true (attr-starts :href "http://cgr" :title "ho")))

(with-test
  (def #^{:doc "Selector predicate, tests if the specified attributes end with the specified values. See CSS $= ."} 
   attr-ends
    (multi-attr-pred ends-with?))

  (are (test-step _1 _2 (elt :a {:href "http://cgrand.net/" :title "home"}))
    true (attr-ends :href "d.net/")
    false (attr-ends :href "e.org/")
    false (attr-ends :href "d.net/" :name "me")
    false (attr-ends :href "d.net/" :title "hom")
    true (attr-ends :href "d.net/" :title "me")))

(with-test
  (def #^{:doc "Selector predicate, tests if the specified attributes contain the specified values. See CSS *= ."} 
   attr-contains
    (multi-attr-pred contains-substring?))
    
  (are (test-step _1 _2 (elt :a {:href "http://cgrand.net/" :title "home"}))
    true (attr-contains :href "rand")
    false (attr-contains :href "jure")
    false (attr-contains :href "rand" :name "om") 
    false (attr-contains :href "rand" :title "pa")
    true (attr-contains :href "rand" :title "om")))

(defn- is-first-segment? [#^String s #^String segment]
  (and s 
    (.startsWith s segment)
    (= \- (.charAt s (count segment)))))
             
(def #^{:doc "Selector predicate, tests if the specified attributes start with the specified values. See CSS |= ."}
 attr|=           
  (multi-attr-pred is-first-segment?))

(def root 
  (sm/pred #(-> % z/up nil?)))

(defn- nth? 
 [f a b]
  (if (zero? a)
    #(= (-> (f %) count inc) b)
    #(let [an+b (-> (filter map? (f %)) count inc)
           an (- an+b b)]
       (and (zero? (rem an a)) (<= 0 (quot an a))))))

(with-test      
  (defn nth-child
   "Selector step, tests if the node has an+b-1 siblings on its left. See CSS :nth-child."
   ([b] (nth-child 0 b))
   ([a b] (sm/pred (nth? z/lefts a b))))

  (are (same? _2 (at (html-src "<dl><dt>1<dt>2<dt>3<dt>4<dt>5") _1 (add-class "foo")))    
    [[:dt (nth-child 2)]] "<dl><dt>1<dt class=foo>2<dt>3<dt>4<dt>5" 
    [[:dt (nth-child 2 0)]] "<dl><dt>1<dt class=foo>2<dt>3<dt class=foo>4<dt>5" 
    [[:dt (nth-child 3 1)]] "<dl><dt class=foo>1<dt>2<dt>3<dt class=foo>4<dt>5" 
    [[:dt (nth-child -1 3)]] "<dl><dt class=foo>1<dt class=foo>2<dt class=foo>3<dt>4<dt>5" 
    [[:dt (nth-child 3 -1)]] "<dl><dt>1<dt class=foo>2<dt>3<dt>4<dt class=foo>5"))
      
(with-test      
  (defn nth-last-child
   "Selector step, tests if the node has an+b-1 siblings on its right. See CSS :nth-last-child."
   ([b] (nth-last-child 0 b))
   ([a b] (sm/pred (nth? z/rights a b))))

  (are (same? _2 (at (html-src "<dl><dt>1<dt>2<dt>3<dt>4<dt>5") _1 (add-class "foo")))    
    [[:dt (nth-last-child 2)]] "<dl><dt>1<dt>2<dt>3<dt class=foo>4<dt>5" 
    [[:dt (nth-last-child 2 0)]] "<dl><dt>1<dt class=foo>2<dt>3<dt class=foo>4<dt>5" 
    [[:dt (nth-last-child 3 1)]] "<dl><dt>1<dt class=foo>2<dt>3<dt>4<dt class=foo>5" 
    [[:dt (nth-last-child -1 3)]] "<dl><dt>1<dt>2<dt class=foo>3<dt class=foo>4<dt class=foo>5" 
    [[:dt (nth-last-child 3 -1)]] "<dl><dt class=foo>1<dt>2<dt>3<dt class=foo>4<dt>5"))

(defn- filter-of-type [f]
  (fn [loc]
    (let [tag (-> loc z/node :tag)
          pred #(= (:tag %) tag)]
      (filter pred (f loc)))))

(with-test
  (defn nth-of-type
   "Selector step, tests if the node has an+b-1 siblings of the same type (tag name) on its left. See CSS :nth-of-type."
   ([b] (nth-of-type 0 b))
   ([a b] (sm/pred (nth? (filter-of-type z/lefts) a b))))
   
  (are (same? _2 (at (html-src "<dl><dt>1<dd>def #1<dt>2<dt>3<dd>def #3<dt>4<dt>5") _1 (add-class "foo")))    
    [[:dt (nth-of-type 2)]] "<dl><dt>1<dd>def #1<dt class=foo>2<dt>3<dd>def #3<dt>4<dt>5" 
    [[:dt (nth-of-type 2 0)]] "<dl><dt>1<dd>def #1<dt class=foo>2<dt>3<dd>def #3<dt class=foo>4<dt>5" 
    [[:dt (nth-of-type 3 1)]] "<dl><dt class=foo>1<dd>def #1<dt>2<dt>3<dd>def #3<dt class=foo>4<dt>5" 
    [[:dt (nth-of-type -1 3)]] "<dl><dt class=foo>1<dd>def #1<dt class=foo>2<dt class=foo>3<dd>def #3<dt>4<dt>5" 
    [[:dt (nth-of-type 3 -1)]] "<dl><dt>1<dd>def #1<dt class=foo>2<dt>3<dd>def #3<dt>4<dt class=foo>5"))
   
(with-test
  (defn nth-last-of-type
   "Selector step, tests if the node has an+b-1 siblings of the same type (tag name) on its right. See CSS :nth-last-of-type."
   ([b] (nth-last-of-type 0 b))
   ([a b] (sm/pred (nth? (filter-of-type z/rights) a b))))
  
  (are (same? _2 (at (html-src "<dl><dt>1<dd>def #1<dt>2<dt>3<dd>def #3<dt>4<dt>5") _1 (add-class "foo")))    
    [[:dt (nth-last-of-type 2)]] "<dl><dt>1<dd>def #1<dt>2<dt>3<dd>def #3<dt class=foo>4<dt>5" 
    [[:dt (nth-last-of-type 2 0)]] "<dl><dt>1<dd>def #1<dt class=foo>2<dt>3<dd>def #3<dt class=foo>4<dt>5" 
    [[:dt (nth-last-of-type 3 1)]] "<dl><dt>1<dd>def #1<dt class=foo>2<dt>3<dd>def #3<dt>4<dt class=foo>5" 
    [[:dt (nth-last-of-type -1 3)]] "<dl><dt>1<dd>def #1<dt>2<dt class=foo>3<dd>def #3<dt class=foo>4<dt class=foo>5" 
    [[:dt (nth-last-of-type 3 -1)]] "<dl><dt class=foo>1<dd>def #1<dt>2<dt>3<dd>def #3<dt class=foo>4<dt>5"))

(def first-child (nth-child 1))      
      
(def last-child (nth-last-child 1))      
      
(def first-of-type (nth-of-type 1))      
      
(def last-of-type (nth-last-of-type 1))      

(def only-child (sm/intersection first-child last-child))  

(def only-of-type (sm/intersection first-of-type last-of-type))

(def void (pred #(empty? (remove empty? (:content %)))))

(def odd (nth-child 2 1))

(def even (nth-child 2 0))

(defn- select? [nodes state]
  (boolean (seq (select* nodes state))))

(defn has* [state]
  (pred #(select? [%] state)))

(defmacro has
 "Selector predicate, matches elements which contain at least one element that matches the specified selector. See jQuery's :has" 
 [selector]
  `(has* (sm/chain any (selector ~selector))))

(set-test has    
  (is-same "<div><p>XXX<p class='ok'><a>link</a><p>YYY" 
    (at (html-src "<div><p>XXX<p><a>link</a><p>YYY") 
      [[:p (has [:a])]] (add-class "ok"))))

(defmacro but
 "Selector predicate, matches elements which are rejected by the specified selector-step. See CSS :not" 
 [selector-step]
  `(sm/complement-next (selector-step ~selector-step)))

(set-test but    
  (is-same "<div><p>XXX<p><a class='ok'>link</a><p>YYY" 
    (at (html-src "<div><p>XXX<p><a>link</a><p>YYY") 
      [:div (but :p)] (add-class "ok")))
      
  (is-same "<div><p class='ok'>XXX<p><a>link</a><p class='ok'>YYY" 
    (at (html-src "<div><p>XXX<p><a>link</a><p>YYY") 
      [[:p (but (has [:a]))]] (add-class "ok"))))

(defn left* [state]
 (sm/pred 
   #(when-let [sibling (first (filter map? (reverse (z/lefts %))))]
      (select? [sibling] state))))

(defmacro left 
 [selector-step]
  `(left* (selector-step ~selector-step)))

(set-test left
  (are (same? _2 (at (html-src "<h1>T1<h2>T2<h3>T3<p>XXX") _1 (add-class "ok"))) 
    [[:h3 (left :h2)]] "<h1>T1<h2>T2<h3 class=ok>T3<p>XXX" 
    [[:h3 (left :h1)]] "<h1>T1<h2>T2<h3>T3<p>XXX" 
    [[:h3 (left :p)]] "<h1>T1<h2>T2<h3>T3<p>XXX"))

(defn lefts* [state]
 (sm/pred 
   #(select? (filter map? (z/lefts %)) state)))
  
(defmacro lefts
 [selector-step]
  `(lefts* (selector-step ~selector-step)))

(set-test lefts
  (are (same? _2 (at (html-src "<h1>T1<h2>T2<h3>T3<p>XXX") _1 (add-class "ok"))) 
    [[:h3 (lefts :h2)]] "<h1>T1<h2>T2<h3 class=ok>T3<p>XXX" 
    [[:h3 (lefts :h1)]] "<h1>T1<h2>T2<h3 class=ok>T3<p>XXX" 
    [[:h3 (lefts :p)]] "<h1>T1<h2>T2<h3>T3<p>XXX")) 
      

(defn right* [state]
 (sm/pred 
   #(when-let [sibling (first (filter map? (z/rights %)))]
      (select? [sibling] state))))

(defmacro right 
 [selector-step]
  `(right* (selector-step ~selector-step)))

(set-test right
  (are (same? _2 (at (html-src "<h1>T1<h2>T2<h3>T3<p>XXX") _1 (add-class "ok"))) 
    [[:h2 (right :h3)]] "<h1>T1<h2 class=ok>T2<h3>T3<p>XXX" 
    [[:h2 (right :p)]] "<h1>T1<h2>T2<h3>T3<p>XXX" 
    [[:h2 (right :h1)]] "<h1>T1<h2>T2<h3>T3<p>XXX")) 

(defn rights* [state]
 (sm/pred 
   #(select? (filter map? (z/rights %)) state)))
  
(defmacro rights 
 [selector-step]
  `(rights* (selector-step ~selector-step)))

(set-test rights  
  (are (same? _2 (at (html-src "<h1>T1<h2>T2<h3>T3<p>XXX") _1 (add-class "ok"))) 
    [[:h2 (rights :h3)]] "<h1>T1<h2 class=ok>T2<h3>T3<p>XXX" 
    [[:h2 (rights :p)]] "<h1>T1<h2 class=ok>T2<h3>T3<p>XXX" 
    [[:h2 (rights :h1)]] "<h1>T1<h2>T2<h3>T3<p>XXX")) 

;; tests that are easier to define once everything exists 
(set-test transform
  (is-same "<div>" (at (html-src "<div><span>") [:span] nil)))
  
(set-test clone-for
  (is-same "<ul><li>one<li>two" (at (html-src "<ul><li>") [:li] (clone-for [x ["one" "two"]] (content x))))) 

(set-test move
  (are (same? _2 ((move [:span] [:div] _1) (html-src "<span>1</span><div id=target>here</div><span>2</span>")))
  substitute "<span>1</span><span>2</span>"
  content "<div id=target><span>1</span><span>2</span></div>"
  after "<div id=target>here</div><span>1</span><span>2</span>"
  before "<span>1</span><span>2</span><div id=target>here</div>"
  append "<div id=target>here<span>1</span><span>2</span></div>"
  prepend "<div id=target><span>1</span><span>2</span>here</div>"))
  
(set-test select 
  (is (= 3 (count (select (htmlize "<h1>hello</h1>") [:*])))))