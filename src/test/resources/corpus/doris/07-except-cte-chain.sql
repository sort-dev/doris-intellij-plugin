with foo as (
    select * EXCEPT(event_sub_type) from events AS e
), bar AS (
    select * from foo AS f
)
select * from bar AS b;
