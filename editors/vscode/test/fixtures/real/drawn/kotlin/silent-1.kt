div {
  dataSignals("fooBar", "1")
  dataOn("widgetLoaded", "x()") { once = true }
  dataClass("isOpen", "$" + "open")
  dataStyle("backgroundColor", "'red'")
  dataSignals("MySignal", "1")
  dataOn("customEvent", "x()") { case = Case.KEBAB }
  dataSignals("{fooBar: 1}")
  dataBind("fooBar")
  dataOnClick("@post('/Add')", { once = true })
  dataOnFetch("evt.detail.type === 'Started'") { window = true }
  dataRef("myRef")
  dataIndicator("isLoading")
  dataSignals("{fooBar: 1}", true)
  dataPersist("myKey")
  dataMatchMedia("isMobile", mediaQuery = "(max-width: 600px)")
}
