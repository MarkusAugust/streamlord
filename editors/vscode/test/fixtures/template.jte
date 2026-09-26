@import no.fristil.Tilstand
@param Tilstand tilstand
<!-- Hver linje med kommentar skal gi markeringen kommentaren beskriver. -->
<div data-signals="{count: ${tilstand.count}}">                      <!-- ingen markering (JTE-uttrykk tolereres) -->
  <span data-text="${tilstand.navn}"></span>                         <!-- ingen markering -->
  <span data-text="!{tilstand.raw}"></span>                          <!-- ingen markering (uescapet JTE-uttrykk) -->
  @if(tilstand.open)
  <p data-show="$open" class="@if(tilstand.x)a@endif">x</p>          <!-- ingen markering -->
  @endif
  @for(var sak : tilstand.saker)
  <li data-on:click__debunce.500ms="@post('/x')">${sak}</li>          <!-- RØD: ukjent modifier -->
  @endfor
  <div data-on:click="@if(a) x() @endif"></div>                      <!-- ingen markering (JTE-kontrollflyt i verdien) -->
  <div data-text="$count +"></div>                                   <!-- RØD: syntaksfeil -->
  <%-- <div data-text=""> i en JTE-kommentar --%>
</div>
