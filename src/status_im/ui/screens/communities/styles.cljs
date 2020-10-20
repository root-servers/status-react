(ns status-im.ui.screens.communities.styles
  (:require
   [quo.core :as quo]
   [status-im.ui.components.colors :as colors]))

(def footer-content
  {:border-top-width 1
   :border-top-color colors/gray-lighter
   :padding-top 15
   :height         52})

(def footer-text {:color      colors/blue
                  :text-align :center})

