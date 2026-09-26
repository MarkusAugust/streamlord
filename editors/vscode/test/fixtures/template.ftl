<#-- Hver linje med kommentar skal gi markeringen kommentaren beskriver. <div data-text=""> her er kommentar. -->
<#import "/lib.ftl" as lib>
<#assign open = true>
<div data-signals="{count: ${count}}">                                <#-- ingen markering (FreeMarker-uttrykk tolereres) -->
  <#if open>
  <span data-text="$count"></span>                                   <#-- ingen markering -->
  <#else>
  <span data-show="!$open"></span>                                   <#-- ingen markering -->
  </#if>
  <#list saker as sak>
  <li data-on:click="@post('/sak/${sak.id}')">${sak.navn}</li>       <#-- ingen markering -->
  </#list>
  <@lib.knapp data-on:click="x()" />                                 <#-- ingen markering (makrokall er ikke et element) -->
  [#if x]<i data-text="[=count]"></i>[/#if]                          <#-- ingen markering (klammesyntaks) -->
  <div data-signal:foo="1"></div>                                    <#-- GUL: did you mean data-signals -->
  <div data-on="x()"></div>                                          <#-- RØD: data-on trenger nøkkel -->
</div>
