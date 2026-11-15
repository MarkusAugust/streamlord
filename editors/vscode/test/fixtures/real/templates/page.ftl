<#-- @ftlvariable name="hero" type="app.Hero" -->
<section id="hero" data-signals:hp="${hero.hp}">
  <#if hero.alive>
    <p data-text="$hp + ' of ${hero.maxHp}'"></p>
  <#else>
    <p data-text="'${hero.name?js_string} has fallen'"></p>
  </#if>
  <button data-on:click="$hp-- ; @post('/heroes/${hero.id}/hit')">Hit</button>
  <button data-on:click__once.twice="$hp = 0">Slay</button>
</section>
